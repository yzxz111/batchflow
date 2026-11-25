import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import json
import os
import platform
import re
import sys
import tempfile
import time
import traceback
import uuid
from pathlib import Path
from urllib.parse import urlsplit

BASE_URL = os.environ.get("BATCHFLOW_E2E_URL", "http://127.0.0.1:18090").rstrip("/")
PROJECT_ROOT = Path(__file__).resolve().parent.parent
STORAGE_DIR = Path(os.environ.get("BATCHFLOW_STORAGE_DIR", str(PROJECT_ROOT / ".runtime" / "storage")))
SCENARIOS = [
    ("overview_and_worker_status", "functional"),
    ("template_csv_download_and_bom", "functional"),
    ("full_import_120_rows_with_error_csv_formula_escaping", "functional"),
    ("product_search_and_task_link", "functional"),
    ("cancel_running_task_stops_subsequent_batches", "functional"),
    ("failed_task_manual_retry_from_zero_checkpoint", "functional"),
    ("offline_error_banner_and_recovery", "functional"),
    ("narrow_viewport_390x844_no_overflow", "layout"),
    ("zero_unexpected_console_or_page_errors", "diagnostic"),
]


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def safe_text(value: object) -> str:
    text = str(value)
    text = re.sub(r"(?i)(authorization|password|api[_-]?key|access[_-]?token)([\s\"'=:\\]+)[^\s\"',;]+", r"\1\2[REDACTED]", text)
    text = re.sub(r"(?i)bearer\s+[A-Za-z0-9._~+/-]+=*", "Bearer [REDACTED]", text)
    return text[:12000]


def save_report(report: dict, report_dir: Path) -> None:
    temporary = report_dir / "report.json.tmp"
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(report_dir / "report.json")


def start_scenario(report: dict, scenario_id: str, report_dir: Path) -> None:
    finish_scenario(report)
    scenario = next(item for item in report["scenarios"] if item["id"] == scenario_id)
    scenario.update(status="RUNNING", startedAt=utc_now())
    report["activeScenarioId"] = scenario_id
    save_report(report, report_dir)


def finish_scenario(report: dict, status: str = "PASSED") -> None:
    active = report.get("activeScenarioId")
    if active:
        scenario = next(item for item in report["scenarios"] if item["id"] == active)
        scenario.update(status=status, finishedAt=utc_now())
        report["activeScenarioId"] = None


@contextmanager
def traced_page(playwright, report: dict, report_dir: Path):
    browser = None
    context = None
    trace_started = False
    try:
        executable = find_browser_executable()
        report["runtime"]["browserExecutable"] = executable
        browser = playwright.chromium.launch(executable_path=executable, headless=True)
        report["runtime"]["browserVersion"] = browser.version
        context = browser.new_context(viewport={"width": 1440, "height": 900}, accept_downloads=True)
        context.tracing.start(screenshots=True, snapshots=True, sources=False)
        trace_started = True
        yield context, context.new_page()
    except BaseException:
        if context and context.pages:
            try:
                context.pages[-1].screenshot(path=str(report_dir / "screenshots" / "failure.png"))
            except Exception as error:
                report["cleanupErrors"].append(f"Failure screenshot unavailable: {safe_text(error)}")
        raise
    finally:
        primary_failure = sys.exc_info()[0] is not None
        cleanup_errors = []
        if trace_started:
            try:
                context.tracing.stop(path=str(report_dir / "trace.zip"))
                report["artifacts"]["trace"].update(status="CAPTURED", path="trace.zip")
            except Exception as error:
                cleanup_errors.append(f"Trace save failed: {safe_text(error)}")
                report["artifacts"]["trace"].update(status="FAILED", error=safe_text(error))
        if browser:
            try:
                browser.close()
            except Exception as error:
                cleanup_errors.append(f"Browser close failed: {safe_text(error)}")
        report["cleanupErrors"].extend(cleanup_errors)
        if cleanup_errors and not primary_failure:
            raise RuntimeError("; ".join(cleanup_errors))


def task_snapshot(page, task_id: str) -> dict:
    response = page.request.get(f"{BASE_URL}/api/tasks/{task_id}")
    assert_true(response.ok, f"任务核对请求失败: HTTP {response.status}")
    task = response.json()
    fields = ["id", "status", "totalRows", "processedRows", "successRows", "errorRows", "checkpoint", "attempt", "maxAttempts"]
    return {field: task.get(field) for field in fields}


def assert_narrow_layout(page, label: str, selector: str | None, report: dict) -> None:
    metrics = page.evaluate("""selector => {
      const root = document.documentElement;
      const element = selector ? document.querySelector(selector) : root;
      if (!element) throw new Error(`Layout target missing: ${selector}`);
      const box = element.getBoundingClientRect();
      return {viewportWidth: window.innerWidth, documentScrollWidth: root.scrollWidth,
        targetClientWidth: element.clientWidth, targetScrollWidth: element.scrollWidth,
        targetWidth: box.width, targetLeft: box.left, targetRight: box.right};
    }""", selector)
    report["observations"].setdefault("narrowLayouts", {})[label] = metrics
    assert_true(metrics["documentScrollWidth"] <= metrics["viewportWidth"] + 1, f"窄屏 {label} 页面横向溢出: {metrics}")
    assert_true(metrics["targetScrollWidth"] <= metrics["targetClientWidth"] + 1, f"窄屏 {label} 内容横向溢出: {metrics}")
    assert_true(metrics["targetLeft"] >= -1 and metrics["targetRight"] <= metrics["viewportWidth"] + 1, f"窄屏 {label} 外框超出视口: {metrics}")


def assert_true(cond: bool, msg: str) -> None:
    if not cond:
        raise AssertionError(msg)


def find_browser_executable() -> str:
    for candidate in [
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    ]:
        if Path(candidate).exists():
            return candidate
    raise RuntimeError("未找到本机 Chrome 或 Edge 浏览器可执行文件")


def make_e2e_csv(path: Path, prefix: str, total_rows: int = 120) -> None:
    lines = ["\ufeffsku,name,category,price,stock\r\n"]
    # 15 explicit invalid rows (triggers 2-page error pagination, size=10) including formula injection
    lines.append(f"=CMD|'/C calc'!A0,危险公式商品,办公,10.00,1\r\n")
    lines.append(f"{prefix}-ERR-PRICE,负价格商品,办公,-5.50,2\r\n")
    lines.append(f"{prefix}-ERR-STOCK,负库存商品,办公,19.90,-3\r\n")
    lines.append(f"{prefix}-1,正常商品1,办公,29.90,10\r\n")
    lines.append(f"{prefix}-1,重复SKU商品,办公,99.00,5\r\n")
    lines.append(f"{prefix}-ERR-NAME,,办公,12.00,5\r\n")
    for err_idx in range(1, 11):
        lines.append(f"{prefix}-ERR-BATCH-{err_idx},无效价格{err_idx},办公,0,1\r\n")
    # 16 rows above (1 valid + 15 invalid); add 104 more valid rows -> 120 total (105 valid, 15 invalid)
    for idx in range(2, total_rows - 14):
        lines.append(f"{prefix}-{idx},E2E测试商品{idx},测试类目,15.50,8\r\n")
    path.write_text("".join(lines), encoding="utf-8")


def make_valid_csv(path: Path, prefix: str, rows: int) -> None:
    lines = ["\ufeffsku,name,category,price,stock\r\n"]
    for idx in range(1, rows + 1):
        lines.append(f"{prefix}-{idx},批量商品{idx},办公,18.80,12\r\n")
    path.write_text("".join(lines), encoding="utf-8")


def execute(report: dict, report_dir: Path) -> None:
    from playwright.sync_api import sync_playwright

    screenshot_dir = report_dir / "screenshots"
    screenshot_dir.mkdir()
    run_tag = report["runTag"]
    diagnostics = report["diagnostics"]
    intentional_offline_phase = False

    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        e2e_csv = tmp / f"e2e-{run_tag}.csv"
        cancel_csv = tmp / f"cancel-{run_tag}.csv"
        retry_csv = tmp / f"retry-{run_tag}.csv"
        make_e2e_csv(e2e_csv, f"E2E-{run_tag}", total_rows=120)
        make_valid_csv(cancel_csv, f"CAN-{run_tag}", rows=400)
        make_valid_csv(retry_csv, f"RET-{run_tag}", rows=20)

        with sync_playwright() as p, traced_page(p, report, report_dir) as (context, page):

            def on_console(msg):
                if msg.type == "error":
                    bucket = "intentionalOfflineConsoleErrors" if intentional_offline_phase else "unexpectedConsoleErrors"
                    diagnostics[bucket].append(safe_text(msg.text))

            def on_pageerror(err):
                bucket = "intentionalOfflinePageErrors" if intentional_offline_phase else "unexpectedPageErrors"
                diagnostics[bucket].append(safe_text(err))

            page.on("console", on_console)
            page.on("pageerror", on_pageerror)

            # 1. Load Overview
            start_scenario(report, "overview_and_worker_status", report_dir)
            page.goto(BASE_URL, wait_until="networkidle")
            page.locator("h1", has_text="BatchFlow").wait_for()
            page.locator(".worker-pill").wait_for()
            worker_pill_text = page.locator(".worker-pill").inner_text()
            assert_true("在线 Worker" in worker_pill_text, f"Worker 状态异常: {worker_pill_text}")
            report["observations"]["workerStatusText"] = worker_pill_text
            page.screenshot(path=str(screenshot_dir / "01-overview-desktop.png"), full_page=True)

            # 2. Download Template CSV & verify content
            start_scenario(report, "template_csv_download_and_bom", report_dir)
            page.get_by_role("button", name="新建导入任务").click()
            page.locator("#import-title").wait_for()
            with page.expect_download() as dl_info:
                page.get_by_role("button", name="下载示例 CSV").click()
            tpl_dl = dl_info.value
            tpl_path = tmp / "downloaded-template.csv"
            tpl_dl.save_as(str(tpl_path))
            tpl_bytes = tpl_path.read_bytes()
            assert_true(tpl_bytes.startswith(b"\xef\xbb\xbf"), "示例模板缺少 UTF-8 BOM")
            tpl_text = tpl_bytes[3:].decode("utf-8")
            assert_true("sku,name,category,price,stock" in tpl_text, "示例模板缺少表头")
            assert_true("P001,无线鼠标" in tpl_text, "示例模板缺少示例商品行")

            report["observations"]["templateDownload"] = {"bytes": len(tpl_bytes), "utf8Bom": True, "headerAndExampleRowVerified": True}

            # 3. Upload custom 120-row CSV (105 valid, 15 invalid) -> map -> submit
            start_scenario(report, "full_import_120_rows_with_error_csv_formula_escaping", report_dir)
            page.locator('input[type="file"]').set_input_files(str(e2e_csv))
            page.locator(".upload-preview").wait_for(timeout=15000)
            preview_text = page.locator(".upload-preview").inner_text()
            assert_true("120 行数据" in preview_text, f"预览行数异常: {preview_text}")
            assert_true("准备就绪" in preview_text, "未显示准备就绪")

            page.get_by_role("button", name="下一步").click()
            page.locator("#task-name").fill(f"E2E导入-{run_tag}")
            page.locator("#batch-size").fill("30")
            page.locator("#max-attempts").select_option("3")
            assert_true("已关联 5 / 5" in page.locator(".mapping-heading").inner_text(), "字段自动映射未完整关联")
            page.screenshot(path=str(screenshot_dir / "02-wizard-step2.png"))
            with page.expect_response(lambda r: r.url.endswith("/api/tasks") and r.request.method == "POST") as create_info:
                page.get_by_role("button", name="提交导入任务").click()
            import_task_id = create_info.value.json()["id"]

            # Wait for drawer completion
            drawer = page.locator(".task-drawer")
            drawer.wait_for(timeout=10000)
            drawer.locator(".status-badge", has_text="完成 · 有错误").wait_for(timeout=30000)
            drawer_text = drawer.inner_text()
            assert_true("100.0%" in drawer_text, "进度未达 100.0%")
            assert_true("105" in drawer_text and "15" in drawer_text, f"成功/错误数异常: {drawer_text}")
            assert_true("第 120 条数据" in drawer_text, "检查点未达 120")
            imported = task_snapshot(page, import_task_id)
            report["observations"]["importTask"] = imported
            assert_true(imported["processedRows"] == imported["checkpoint"] == 120 and imported["successRows"] == 105 and imported["errorRows"] == 15, f"导入持久化计数异常: {imported}")

            # Verify error pagination (15 errors -> 2 pages) & expand raw data
            assert_true("第 1 / 2 页" in drawer.inner_text(), "错误明细未显示分页第 1 / 2 页")
            drawer.get_by_role("button", name="错误明细下一页").click()
            drawer.locator(".pagination.slim", has_text="第 2 / 2 页").wait_for(timeout=5000)
            drawer.get_by_role("button", name="错误明细上一页").click()
            drawer.locator(".pagination.slim", has_text="第 1 / 2 页").wait_for(timeout=5000)
            drawer.locator(".error-row details summary").first.click()
            assert_true(drawer.locator(".error-row pre").first.is_visible(), "原始数据展开失败")
            page.screenshot(path=str(screenshot_dir / "03-drawer-completed-errors.png"))

            # Download error CSV & verify content + formula escaping
            with page.expect_download() as err_dl_info:
                drawer.get_by_role("button", name="导出 CSV").click()
            err_dl = err_dl_info.value
            err_path = tmp / "downloaded-errors.csv"
            err_dl.save_as(str(err_path))
            err_bytes = err_path.read_bytes()
            assert_true(err_bytes.startswith(b"\xef\xbb\xbf"), "错误导出缺少 UTF-8 BOM")
            err_text = err_bytes[3:].decode("utf-8")
            assert_true("行号,商品编码,错误原因,原始数据" in err_text, "错误导出表头异常")
            assert_true("'=CMD|" in err_text, "错误导出未对公式前缀做防注入转义")
            assert_true(f"E2E-{run_tag}-ERR-PRICE" in err_text, "错误导出缺少预期错误 SKU")
            report["observations"]["errorDownload"] = {"bytes": len(err_bytes), "utf8Bom": True, "headerAndFormulaEscapingVerified": True, "errorPages": 2}

            drawer.get_by_role("button", name="关闭任务详情").click()
            drawer.wait_for(state="hidden", timeout=5000)

            # Verify Products tab search & source task link
            start_scenario(report, "product_search_and_task_link", report_dir)
            page.get_by_role("tab", name="已入库商品").click()
            page.get_by_label("搜索已入库商品").fill(f"E2E-{run_tag}-100")
            page.locator("table.product-table tbody tr", has_text=f"E2E-{run_tag}-100").first.wait_for(timeout=10000)
            page.locator("table.product-table tbody tr td button.text-link").first.click()
            drawer.wait_for(timeout=5000)
            drawer.get_by_role("button", name="关闭任务详情").click()
            drawer.wait_for(state="hidden", timeout=5000)

            # 4. Cancel running task flow (400 rows, batchSize=40 -> 10 batches with slow worker delay)
            start_scenario(report, "cancel_running_task_stops_subsequent_batches", report_dir)
            page.get_by_role("button", name="新建导入任务").click()
            page.locator('input[type="file"]').set_input_files(str(cancel_csv))
            page.locator(".upload-preview").wait_for(timeout=15000)
            page.get_by_role("button", name="下一步").click()
            page.locator("#task-name").fill(f"E2E取消-{run_tag}")
            page.locator("#batch-size").fill("40")
            with page.expect_response(lambda r: r.url.endswith("/api/tasks") and r.request.method == "POST") as cancel_info:
                page.get_by_role("button", name="提交导入任务").click()
            cancel_task_id = cancel_info.value.json()["id"]

            drawer.wait_for(timeout=10000)
            drawer.locator(".status-badge", has_text="处理中").wait_for(timeout=15000)
            # Wait until at least 1 batch committed or cancel immediately while RUNNING
            deadline = time.time() + 15
            while time.time() < deadline:
                if "已处理 0 /" not in drawer.inner_text():
                    break
                time.sleep(0.3)
            drawer.get_by_role("button", name="取消任务").click()
            drawer.locator(".cancel-confirm").wait_for(timeout=5000)
            drawer.get_by_role("button", name="确认取消任务").click()
            drawer.locator(".status-badge", has_text="已取消").wait_for(timeout=10000)
            cancelled_snapshot = drawer.locator(".progress-footer").inner_text()
            cancel_before = task_snapshot(page, cancel_task_id)
            time.sleep(2.5)
            cancel_after = task_snapshot(page, cancel_task_id)
            report["observations"]["cancellation"] = {"before": cancel_before, "after": cancel_after, "observationWindowSeconds": 2.5}
            assert_true(cancel_before["status"] == cancel_after["status"] == "CANCELLED" and cancel_before["processedRows"] == cancel_after["processedRows"], f"取消后持久化计数变化: {cancel_after}")
            assert_true(
                drawer.locator(".progress-footer").inner_text() == cancelled_snapshot,
                "取消后已处理行数仍在变化",
            )
            page.screenshot(path=str(screenshot_dir / "04-drawer-cancelled.png"))
            drawer.get_by_role("button", name="关闭任务详情").click()
            drawer.wait_for(state="hidden", timeout=5000)

            # 5. Failure before processing -> manual retry from checkpoint 0.
            start_scenario(report, "failed_task_manual_retry_from_zero_checkpoint", report_dir)
            page.get_by_role("button", name="新建导入任务").click()
            with page.expect_response(lambda r: r.url.endswith("/api/uploads") and r.request.method == "POST") as up_resp_info:
                page.locator('input[type="file"]').set_input_files(str(retry_csv))
            upload_json = up_resp_info.value.json()
            retry_upload_id = upload_json["uploadId"]
            page.locator(".upload-preview").wait_for(timeout=15000)
            page.get_by_role("button", name="下一步").click()
            page.locator("#task-name").fill(f"E2E重试-{run_tag}")
            page.locator("#batch-size").fill("10")
            page.locator("#max-attempts").select_option("1")

            stored_file = STORAGE_DIR / f"{retry_upload_id}.csv"
            backup_file = STORAGE_DIR / f"{retry_upload_id}.csv.e2ebak"
            assert_true(stored_file.exists(), f"未找到上传存储文件: {stored_file}")
            stored_file.rename(backup_file)
            try:
                with page.expect_response(lambda r: r.url.endswith("/api/tasks") and r.request.method == "POST") as retry_info:
                    page.get_by_role("button", name="提交导入任务").click()
                retry_task_id = retry_info.value.json()["id"]
                drawer.wait_for(timeout=10000)
                drawer.locator(".status-badge", has_text="执行失败").wait_for(timeout=20000)
                assert_true("最近执行异常" in drawer.inner_text(), "失败任务未显示最近执行异常")
                failed = task_snapshot(page, retry_task_id)
                report["observations"]["manualRetry"] = {"failedTask": failed, "coverage": "Manual retry from checkpoint 0; does not establish nonzero-checkpoint recovery."}
                assert_true(failed["checkpoint"] == 0 and failed["processedRows"] == 0, f"该手动重试场景预期检查点0: {failed}")
                page.screenshot(path=str(screenshot_dir / "05-drawer-failed.png"))
            finally:
                if backup_file.exists() and not stored_file.exists():
                    backup_file.rename(stored_file)

            drawer.get_by_role("button", name="从检查点重试").click()
            drawer.locator(".status-badge", has_text="已完成").wait_for(timeout=25000)
            assert_true("已处理 20 / 20 行" in drawer.inner_text(), "重试后未完成全部 20 行")
            retried = task_snapshot(page, retry_task_id)
            report["observations"]["manualRetry"]["completedTask"] = retried
            assert_true(retried["status"] == "COMPLETED" and retried["processedRows"] == retried["successRows"] == retried["checkpoint"] == 20 and retried["errorRows"] == 0, f"重试后持久化计数异常: {retried}")
            drawer.get_by_role("button", name="关闭任务详情").click()
            drawer.wait_for(state="hidden", timeout=5000)

            # 6. Offline / network error & recovery
            start_scenario(report, "offline_error_banner_and_recovery", report_dir)
            page.get_by_role("tab", name="导入任务").click()
            page.locator("table.task-table").wait_for(timeout=10000)
            intentional_offline_phase = True
            diagnostics["intentionalOfflineWindow"]["startedAt"] = utc_now()
            context.set_offline(True)
            page.get_by_role("button", name="刷新").click()
            alert = page.locator(".alert.error[role='alert']")
            alert.wait_for(timeout=10000)
            assert_true("暂时无法连接服务" in alert.inner_text(), f"断网提示不符合预期: {alert.inner_text()}")
            page.screenshot(path=str(screenshot_dir / "06-offline-alert.png"))

            context.set_offline(False)
            page.get_by_role("button", name="重新加载").click()
            alert.wait_for(state="hidden", timeout=10000)
            diagnostics["intentionalOfflineWindow"]["finishedAt"] = utc_now()
            intentional_offline_phase = False

            # 7. Narrow viewport (390x844) layout verification
            start_scenario(report, "narrow_viewport_390x844_no_overflow", report_dir)
            page.set_viewport_size({"width": 390, "height": 844})
            page.get_by_role("tab", name="总览工作台").click()
            page.wait_for_timeout(400)
            assert_narrow_layout(page, "overview", None, report)
            page.screenshot(path=str(screenshot_dir / "07-narrow-overview.png"), full_page=True)

            page.get_by_role("button", name="新建导入任务").click()
            page.locator("#import-title").wait_for()
            page.wait_for_timeout(200)
            assert_narrow_layout(page, "importWizard", ".import-dialog", report)
            page.screenshot(path=str(screenshot_dir / "08-narrow-wizard.png"))
            page.get_by_role("button", name="关闭导入窗口").click()

            page.get_by_role("tab", name="导入任务").click()
            page.locator("table.task-table tbody tr button.task-name").first.click()
            drawer.wait_for(timeout=5000)
            page.wait_for_timeout(200)
            assert_narrow_layout(page, "taskDrawer", ".task-drawer", report)
            page.screenshot(path=str(screenshot_dir / "09-narrow-drawer.png"))

    start_scenario(report, "zero_unexpected_console_or_page_errors", report_dir)
    assert_true(not diagnostics["unexpectedConsoleErrors"], f"检测到非故意断网阶段控制台错误: {diagnostics['unexpectedConsoleErrors']}")
    assert_true(not diagnostics["unexpectedPageErrors"], f"检测到非故意断网阶段页面异常: {diagnostics['unexpectedPageErrors']}")
    finish_scenario(report)


def main() -> int:
    parser = argparse.ArgumentParser(description="Run local browser acceptance and retain an isolated report, screenshots, and full Playwright trace.")
    parser.add_argument("--report-dir", type=Path, help="Exact output directory for this run; must not already exist.")
    args = parser.parse_args()
    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ") + "-" + uuid.uuid4().hex[:8]
    report_dir = (args.report_dir or PROJECT_ROOT / ".runtime" / "evidence" / "e2e" / run_id).resolve()
    report_dir.mkdir(parents=True, exist_ok=False)
    started = time.perf_counter()
    report = {
        "schemaVersion": 1, "kind": "browser-e2e", "runId": run_id, "runTag": uuid.uuid4().hex[:8].upper(),
        "status": "RUNNING", "startedAt": utc_now(), "finishedAt": None, "durationMs": None,
        "reportDirectory": str(report_dir), "baseUrl": None,
        "source": {"script": "scripts/e2e-browser.py", "sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest()},
        "runtime": {"python": platform.python_version(), "platform": platform.system()},
        "scenarioGrouping": {"functional": 7, "layout": 1, "diagnostic": 1, "total": 9},
        "scenarios": [{"id": name, "group": group, "status": "NOT_RUN"} for name, group in SCENARIOS],
        "activeScenarioId": None, "observations": {}, "cleanupErrors": [],
        "diagnostics": {"countingPolicy": "Unexpected error assertions exclude only the deliberate offline-and-recovery window. All errors in that window are retained separately, not silently discarded.",
                        "unexpectedConsoleErrors": [], "unexpectedPageErrors": [], "intentionalOfflineConsoleErrors": [], "intentionalOfflinePageErrors": [], "intentionalOfflineWindow": {}},
        "artifacts": {"screenshots": [], "trace": {"status": "NOT_STARTED", "path": None,
                     "scope": "Fresh unauthenticated loopback-only context and synthetic CSV fixtures; full actions/network/snapshots/screenshots, source capture disabled. No environment, cookies, authorization headers, or credentials are added by this script."}},
        "limitations": ["Manual retry deliberately fails before the first batch, so its original checkpoint is 0.", "This run checks one local API/browser workflow; nonzero checkpoint recovery is established by separate backend/Worker recovery evidence."]
    }
    exit_code = 0
    save_report(report, report_dir)
    try:
        parsed = urlsplit(BASE_URL)
        assert_true(parsed.scheme in ("http", "https") and parsed.hostname in ("127.0.0.1", "localhost", "::1") and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment and parsed.path in ("", "/"), "BATCHFLOW_E2E_URL must be a loopback HTTP(S) origin without credentials, query, fragment, or path; trace capture is restricted to local synthetic data.")
        report["baseUrl"] = BASE_URL.rstrip("/")
        execute(report, report_dir)
        report["status"] = "PASSED"
    except BaseException as error:
        exit_code = 130 if isinstance(error, KeyboardInterrupt) else 1
        report["status"] = "FAILED"
        finish_scenario(report, "FAILED")
        report["failure"] = {"type": type(error).__name__, "message": safe_text(error), "traceback": safe_text(traceback.format_exc())}
    finally:
        report["finishedAt"] = utc_now()
        report["durationMs"] = round((time.perf_counter() - started) * 1000, 3)
        report["artifacts"]["screenshots"] = [str(path.relative_to(report_dir)) for path in sorted((report_dir / "screenshots").glob("*.png"))]
        report["scenarioCounts"] = {status: sum(item["status"] == status for item in report["scenarios"]) for status in ("PASSED", "FAILED", "NOT_RUN")}
        report["diagnostics"]["counts"] = {key: len(report["diagnostics"][key]) for key in ("unexpectedConsoleErrors", "unexpectedPageErrors", "intentionalOfflineConsoleErrors", "intentionalOfflinePageErrors")}
        save_report(report, report_dir)
    print(json.dumps({"status": report["status"], "report": str(report_dir / "report.json"), "scenarioCounts": report["scenarioCounts"]}, ensure_ascii=False, indent=2))
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
