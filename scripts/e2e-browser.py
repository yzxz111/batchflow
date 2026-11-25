import json
import os
import sys
import tempfile
import time
import uuid
from pathlib import Path
from playwright.sync_api import sync_playwright

BASE_URL = os.environ.get("BATCHFLOW_E2E_URL", "http://127.0.0.1:18090")
PROJECT_ROOT = Path(__file__).resolve().parent.parent
STORAGE_DIR = Path(os.environ.get("BATCHFLOW_STORAGE_DIR", str(PROJECT_ROOT / ".runtime" / "storage")))
SCREENSHOT_DIR = Path(os.environ.get("BATCHFLOW_E2E_SCREENSHOTS", str(PROJECT_ROOT / ".runtime" / "e2e-screenshots")))


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


def main() -> int:
    SCREENSHOT_DIR.mkdir(parents=True, exist_ok=True)
    run_tag = uuid.uuid4().hex[:8].upper()
    console_errors: list[str] = []
    page_errors: list[str] = []
    allow_network_errors = False

    with tempfile.TemporaryDirectory() as tmpdir:
        tmp = Path(tmpdir)
        e2e_csv = tmp / f"e2e-{run_tag}.csv"
        cancel_csv = tmp / f"cancel-{run_tag}.csv"
        retry_csv = tmp / f"retry-{run_tag}.csv"
        make_e2e_csv(e2e_csv, f"E2E-{run_tag}", total_rows=120)
        make_valid_csv(cancel_csv, f"CAN-{run_tag}", rows=400)
        make_valid_csv(retry_csv, f"RET-{run_tag}", rows=20)

        with sync_playwright() as p:
            browser = p.chromium.launch(
                executable_path=find_browser_executable(),
                headless=True,
            )
            context = browser.new_context(viewport={"width": 1440, "height": 900}, accept_downloads=True)
            page = context.new_page()

            def on_console(msg):
                if msg.type == "error" and not allow_network_errors:
                    console_errors.append(msg.text)

            def on_pageerror(err):
                if not allow_network_errors:
                    page_errors.append(str(err))

            page.on("console", on_console)
            page.on("pageerror", on_pageerror)

            # 1. Load Overview
            page.goto(BASE_URL, wait_until="networkidle")
            page.locator("h1", has_text="BatchFlow").wait_for()
            page.locator(".worker-pill").wait_for()
            worker_pill_text = page.locator(".worker-pill").inner_text()
            assert_true("在线 Worker" in worker_pill_text, f"Worker 状态异常: {worker_pill_text}")
            page.screenshot(path=str(SCREENSHOT_DIR / "01-overview-desktop.png"), full_page=True)

            # 2. Download Template CSV & verify content
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

            # 3. Upload custom 120-row CSV (115 valid, 5 invalid) -> map -> submit
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
            page.screenshot(path=str(SCREENSHOT_DIR / "02-wizard-step2.png"))
            page.get_by_role("button", name="提交导入任务").click()

            # Wait for drawer completion
            drawer = page.locator(".task-drawer")
            drawer.wait_for(timeout=10000)
            drawer.locator(".status-badge", has_text="完成 · 有错误").wait_for(timeout=30000)
            drawer_text = drawer.inner_text()
            assert_true("100.0%" in drawer_text, "进度未达 100.0%")
            assert_true("105" in drawer_text and "15" in drawer_text, f"成功/错误数异常: {drawer_text}")
            assert_true("第 120 条数据" in drawer_text, "检查点未达 120")

            # Verify error pagination (15 errors -> 2 pages) & expand raw data
            assert_true("第 1 / 2 页" in drawer.inner_text(), "错误明细未显示分页第 1 / 2 页")
            drawer.get_by_role("button", name="错误明细下一页").click()
            drawer.locator(".pagination.slim", has_text="第 2 / 2 页").wait_for(timeout=5000)
            drawer.get_by_role("button", name="错误明细上一页").click()
            drawer.locator(".pagination.slim", has_text="第 1 / 2 页").wait_for(timeout=5000)
            drawer.locator(".error-row details summary").first.click()
            assert_true(drawer.locator(".error-row pre").first.is_visible(), "原始数据展开失败")
            page.screenshot(path=str(SCREENSHOT_DIR / "03-drawer-completed-errors.png"))

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

            drawer.get_by_role("button", name="关闭任务详情").click()
            drawer.wait_for(state="hidden", timeout=5000)

            # Verify Products tab search & source task link
            page.get_by_role("tab", name="已入库商品").click()
            page.get_by_label("搜索已入库商品").fill(f"E2E-{run_tag}-100")
            page.locator("table.product-table tbody tr", has_text=f"E2E-{run_tag}-100").first.wait_for(timeout=10000)
            page.locator("table.product-table tbody tr td button.text-link").first.click()
            drawer.wait_for(timeout=5000)
            drawer.get_by_role("button", name="关闭任务详情").click()
            drawer.wait_for(state="hidden", timeout=5000)

            # 4. Cancel running task flow (400 rows, batchSize=40 -> 10 batches with slow worker delay)
            page.get_by_role("button", name="新建导入任务").click()
            page.locator('input[type="file"]').set_input_files(str(cancel_csv))
            page.locator(".upload-preview").wait_for(timeout=15000)
            page.get_by_role("button", name="下一步").click()
            page.locator("#task-name").fill(f"E2E取消-{run_tag}")
            page.locator("#batch-size").fill("40")
            page.get_by_role("button", name="提交导入任务").click()

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
            time.sleep(2.5)
            assert_true(
                drawer.locator(".progress-footer").inner_text() == cancelled_snapshot,
                "取消后已处理行数仍在变化",
            )
            page.screenshot(path=str(SCREENSHOT_DIR / "04-drawer-cancelled.png"))
            drawer.get_by_role("button", name="关闭任务详情").click()
            drawer.wait_for(state="hidden", timeout=5000)

            # 5. Failed -> Retry from checkpoint flow
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
                page.get_by_role("button", name="提交导入任务").click()
                drawer.wait_for(timeout=10000)
                drawer.locator(".status-badge", has_text="执行失败").wait_for(timeout=20000)
                assert_true("最近执行异常" in drawer.inner_text(), "失败任务未显示最近执行异常")
                page.screenshot(path=str(SCREENSHOT_DIR / "05-drawer-failed.png"))
            finally:
                if backup_file.exists() and not stored_file.exists():
                    backup_file.rename(stored_file)

            drawer.get_by_role("button", name="从检查点重试").click()
            drawer.locator(".status-badge", has_text="已完成").wait_for(timeout=25000)
            assert_true("已处理 20 / 20 行" in drawer.inner_text(), "重试后未完成全部 20 行")
            drawer.get_by_role("button", name="关闭任务详情").click()
            drawer.wait_for(state="hidden", timeout=5000)

            # 6. Offline / network error & recovery
            page.get_by_role("tab", name="导入任务").click()
            page.locator("table.task-table").wait_for(timeout=10000)
            allow_network_errors = True
            context.set_offline(True)
            page.get_by_role("button", name="刷新").click()
            alert = page.locator(".alert.error[role='alert']")
            alert.wait_for(timeout=10000)
            assert_true("暂时无法连接服务" in alert.inner_text(), f"断网提示不符合预期: {alert.inner_text()}")
            page.screenshot(path=str(SCREENSHOT_DIR / "06-offline-alert.png"))

            context.set_offline(False)
            page.get_by_role("button", name="重新加载").click()
            alert.wait_for(state="hidden", timeout=10000)
            allow_network_errors = False

            # 7. Narrow viewport (390x844) layout verification
            page.set_viewport_size({"width": 390, "height": 844})
            page.get_by_role("tab", name="总览工作台").click()
            page.wait_for_timeout(400)
            overflow = page.evaluate("() => document.documentElement.scrollWidth - window.innerWidth")
            if overflow > 1:
                offenders = page.evaluate(
                    "() => [...document.querySelectorAll('*')]"
                    ".map(el => ({tag: el.tagName, cls: String(el.className || ''), right: Math.round(el.getBoundingClientRect().right), width: Math.round(el.getBoundingClientRect().width)}))"
                    ".filter(x => x.right > 391).slice(0, 15)"
                )
                raise AssertionError(f"窄屏 390px 总览页出现横向溢出: {overflow}px, offenders={offenders}")
            page.screenshot(path=str(SCREENSHOT_DIR / "07-narrow-overview.png"), full_page=True)

            page.get_by_role("button", name="新建导入任务").click()
            page.locator("#import-title").wait_for()
            page.wait_for_timeout(200)
            dialog_box = page.locator(".import-dialog").bounding_box()
            assert_true(dialog_box is not None and dialog_box["width"] <= 390, f"窄屏向导弹窗超宽: {dialog_box}")
            page.screenshot(path=str(SCREENSHOT_DIR / "08-narrow-wizard.png"))
            page.get_by_role("button", name="关闭导入窗口").click()

            page.get_by_role("tab", name="导入任务").click()
            page.locator("table.task-table tbody tr button.task-name").first.click()
            drawer.wait_for(timeout=5000)
            page.wait_for_timeout(200)
            drawer_box = drawer.bounding_box()
            assert_true(drawer_box is not None and drawer_box["width"] <= 390, f"窄屏抽屉超宽: {drawer_box}")
            page.screenshot(path=str(SCREENSHOT_DIR / "09-narrow-drawer.png"))

            browser.close()

    assert_true(not console_errors, f"检测到浏览器控制台错误: {console_errors}")
    assert_true(not page_errors, f"检测到未捕获页面异常: {page_errors}")
    print(json.dumps({
        "status": "PASSED",
        "runTag": run_tag,
        "screenshotsDir": str(SCREENSHOT_DIR),
        "verified": [
            "overview_and_worker_status",
            "template_csv_download_and_bom",
            "full_import_120_rows_with_error_csv_formula_escaping",
            "product_search_and_task_link",
            "cancel_running_task_stops_subsequent_batches",
            "failed_task_manual_retry_from_checkpoint",
            "offline_error_banner_and_recovery",
            "narrow_viewport_390x844_no_overflow",
            "zero_console_or_page_errors",
        ],
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
