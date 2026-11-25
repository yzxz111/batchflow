package com.batchflow;

import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CsvAndValidatorTest {

    @TempDir
    Path tempDir;

    private final CsvFiles csvFiles = new CsvFiles();
    private final ProductValidator validator = new ProductValidator();

    private static final Map<String, String> MAPPING = Map.of(
            "sku", "product_code",
            "name", "product_name",
            "category", "product_category",
            "price", "unit_price",
            "stock", "quantity"
    );

    @Test
    void openParsesUtf8BomAndQuotedCommasAndNewlinesAsOneLogicalRecord()
            throws IOException {
        Path file = writeCsv("quoted.csv",
                "\uFEFFsku,name,category,price,stock\r\n"
                        + "SKU-1,\"咖啡, \"\"精选\"\"\n第二行\",饮品,12.50,3\r\n"
                        + "SKU-2,普通商品,,1.00,0\r\n");

        try (var parser = csvFiles.open(file)) {
            assertEquals(
                    List.of("sku", "name", "category", "price", "stock"),
                    parser.getHeaderNames()
            );

            List<CSVRecord> records = parser.getRecords();
            assertEquals(2, records.size());

            CSVRecord first = records.get(0);
            assertAll(
                    () -> assertEquals(1L, first.getRecordNumber()),
                    () -> assertEquals(5, first.size()),
                    () -> assertEquals("SKU-1", first.get("sku")),
                    () -> assertEquals("咖啡, \"精选\"\n第二行", first.get("name")),
                    () -> assertEquals("饮品", first.get("category")),
                    () -> assertEquals("12.50", first.get("price")),
                    () -> assertEquals("3", first.get("stock"))
            );

            CSVRecord second = records.get(1);
            assertAll(
                    () -> assertEquals(2L, second.getRecordNumber()),
                    () -> assertEquals("SKU-2", second.get("sku")),
                    () -> assertEquals("普通商品", second.get("name")),
                    () -> assertEquals("", second.get("category"))
            );
        }
    }

    @Test
    void openRejectsMalformedUtf8() throws IOException {
        Path file = tempDir.resolve("malformed.csv");
        byte[] prefix = "sku,name\nSKU-1,".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[prefix.length + 2];
        System.arraycopy(prefix, 0, bytes, 0, prefix.length);
        bytes[prefix.length] = (byte) 0xC3;
        bytes[prefix.length + 1] = (byte) 0x28;
        Files.write(file, bytes);

        assertThrows(IOException.class, () -> {
            try (var parser = csvFiles.open(file)) {
                parser.getRecords();
            }
        });
    }

    @Test
    void openRejectsDuplicateHeaders() throws IOException {
        Path file = writeCsv("duplicate-headers.csv",
                "sku,name,sku\nSKU-1,Product,SKU-2\n");

        assertThrows(IllegalArgumentException.class, () -> {
            try (var parser = csvFiles.open(file)) {
                parser.getRecords();
            }
        });
    }

    @Test
    void openRejectsEmptyAndWhitespaceOnlyHeaders() throws IOException {
        List<String> headers = List.of(
                ",name",
                "sku,,name",
                "sku,name,",
                "sku,   ,name",
                "sku,\"\",name",
                "sku,\"   \",name"
        );

        for (int i = 0; i < headers.size(); i++) {
            Path file = writeCsv("blank-header-" + i + ".csv",
                    headers.get(i) + "\nvalue,value,value\n");

            assertThrows(IllegalArgumentException.class, () -> {
                try (var parser = csvFiles.open(file)) {
                    parser.getRecords();
                }
            }, "Expected rejection for headers: " + headers.get(i));
        }
    }

    @Test
    void safeCellPrefixesFormulaCharactersIncludingAfterLeadingSpaces() {
        for (String marker : List.of("=", "+", "-", "@")) {
            for (String prefix : List.of("", " ", "   ")) {
                String value = prefix + marker + "123";
                assertEquals("'" + value, CsvFiles.safeCell(value),
                        "Expected neutralization for: " + value);
            }
        }
    }

    @Test
    void safeCellPrefixesLeadingControlCharacters() {
        for (String value : List.of(
                "\t", "\r", "\n",
                "\ttext", "\rtext", "\ntext",
                "\t=1+1", "\r+123", "\n@SUM(A1)"
        )) {
            assertEquals("'" + value, CsvFiles.safeCell(value));
        }
    }

    @Test
    void safeCellPreservesNormalStringsAndConvertsNullToEmptyString() {
        assertEquals("", CsvFiles.safeCell(null));

        for (String value : List.of(
                "",
                " ",
                "   ",
                "Product",
                "普通商品",
                "  Product",
                "SKU-123",
                "user@example.com",
                "1+2",
                "text=123",
                "'=1+1"
        )) {
            assertEquals(value, CsvFiles.safeCell(value));
        }
    }

    @Test
    void validateAcceptsValidProductAndReturnsParsedValues() {
        Models.ProductInput product = validator.validate(validRow(), MAPPING);

        assertAll(
                () -> assertEquals("Abc-123._", product.sku()),
                () -> assertEquals("示例商品", product.name()),
                () -> assertEquals("分类", product.category()),
                () -> assertEquals(new BigDecimal("12.50"), product.price()),
                () -> assertEquals(7, product.stock())
        );
    }

    @Test
    void validateAcceptsEmptyOrUnmappedOptionalCategory() {
        Map<String, String> row = validRow();
        row.put("product_category", "");

        assertEquals("", validator.validate(row, MAPPING).category());

        row.put("product_category", "   ");
        assertEquals("", validator.validate(row, MAPPING).category());

        row.remove("product_category");
        assertEquals("", validator.validate(row, MAPPING).category());

        Map<String, String> mapping = new HashMap<>(MAPPING);
        mapping.remove("category");
        assertEquals("", validator.validate(validRow(), mapping).category());
    }

    @Test
    void validateAcceptsBoundaryLengthsAndMaximumNumericValues() {
        Map<String, String> row = validRow();
        row.put("product_code", "A".repeat(64));
        row.put("product_name", "N".repeat(200));
        row.put("product_category", "C".repeat(100));
        row.put("unit_price", "99999999.99");
        row.put("quantity", "2147483647");

        Models.ProductInput product = validator.validate(row, MAPPING);

        assertAll(
                () -> assertEquals("A".repeat(64), product.sku()),
                () -> assertEquals("N".repeat(200), product.name()),
                () -> assertEquals("C".repeat(100), product.category()),
                () -> assertEquals(new BigDecimal("99999999.99"), product.price()),
                () -> assertEquals(Integer.MAX_VALUE, product.stock())
        );
    }

    @Test
    void validateAcceptsPositivePricesWithAtMostTwoDecimalPlacesAndZeroStock() {
        for (String price : List.of("1", "1.2", "1.23", "0.01")) {
            Map<String, String> row = validRow();
            row.put("unit_price", price);
            row.put("quantity", "0");

            Models.ProductInput product = validator.validate(row, MAPPING);

            assertEquals(new BigDecimal(price), product.price());
            assertEquals(0, product.stock());
        }
    }

    @Test
    void validateStripsSurroundingWhitespace() {
        Map<String, String> row = validRow();
        row.put("product_code", "  SKU-1  ");
        row.put("product_name", "  商品名称  ");
        row.put("product_category", "  分类  ");
        row.put("unit_price", "  12.50  ");
        row.put("quantity", "  7  ");

        Models.ProductInput product = validator.validate(row, MAPPING);

        assertAll(
                () -> assertEquals("SKU-1", product.sku()),
                () -> assertEquals("商品名称", product.name()),
                () -> assertEquals("分类", product.category()),
                () -> assertEquals(new BigDecimal("12.50"), product.price()),
                () -> assertEquals(7, product.stock())
        );
    }

    @Test
    void validateRejectsInvalidSkuRegexAndLength() {
        for (String sku : List.of(
                "", "   ", "A".repeat(65),
                "SKU 1", "SKU/1", "SKU@1", "SKU+1", "商品"
        )) {
            assertInvalid("sku", sku);
        }
    }

    @Test
    void validateRejectsBlankOrOverlongName() {
        for (String name : List.of("", "   ", "\t\r\n", "N".repeat(201))) {
            assertInvalid("name", name);
        }
    }

    @Test
    void validateRejectsOverlongCategory() {
        assertInvalid("category", "C".repeat(101));
    }

    @Test
    void validateRejectsNonNumericPrice() {
        for (String price : List.of("", "   ", "abc", "NaN", "Infinity", "1,000.00")) {
            assertInvalid("price", price);
        }
    }

    @Test
    void validateRejectsNonPositivePrice() {
        for (String price : List.of("0", "0.00", "-0.01", "-1")) {
            assertInvalid("price", price);
        }
    }

    @Test
    void validateRejectsPriceWithMoreThanTwoDecimalPlaces() {
        for (String price : List.of("1.001", "1.230", "0.001")) {
            assertInvalid("price", price);
        }
    }

    @Test
    void validateRejectsPriceAboveMaximum() {
        for (String price : List.of("100000000", "100000000.00", "99999999.999")) {
            assertInvalid("price", price);
        }
    }

    @Test
    void validateRejectsNegativeStock() {
        for (String stock : List.of("-1", "-2147483648")) {
            assertInvalid("stock", stock);
        }
    }

    @Test
    void validateRejectsNonIntegerStock() {
        for (String stock : List.of(
                "", "   ", "abc", "1.0", "1.5", "1e2", "+1", "1,000"
        )) {
            assertInvalid("stock", stock);
        }
    }

    @Test
    void validateRejectsOverflowStock() {
        for (String stock : List.of(
                "2147483648", "9999999999", "10000000000"
        )) {
            assertInvalid("stock", stock);
        }
    }

    private Path writeCsv(String filename, String content) throws IOException {
        return Files.writeString(
                tempDir.resolve(filename), content, StandardCharsets.UTF_8);
    }

    private Map<String, String> validRow() {
        Map<String, String> row = new HashMap<>();
        row.put("product_code", "Abc-123._");
        row.put("product_name", "示例商品");
        row.put("product_category", "分类");
        row.put("unit_price", "12.50");
        row.put("quantity", "7");
        return row;
    }

    private void assertInvalid(String field, String value) {
        Map<String, String> row = validRow();
        row.put(MAPPING.get(field), value);

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(row, MAPPING),
                "Expected invalid " + field + ": " + value);
    }
}
