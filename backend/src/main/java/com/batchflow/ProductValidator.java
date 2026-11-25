package com.batchflow;

import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.util.Map;
import static com.batchflow.Models.*;

@Component
public class ProductValidator {
    public ProductInput validate(Map<String,String> row, Map<String,String> mapping) {
        String sku=value(row,mapping,"sku"), name=value(row,mapping,"name"), category=value(row,mapping,"category");
        if (!sku.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("SKU 必须为 1~64 位字母、数字、点、横线或下划线");
        if (name.isBlank() || name.length()>200) throw new IllegalArgumentException("商品名称必填且不超过 200 字符");
        if (category.length()>100) throw new IllegalArgumentException("分类不能超过 100 字符");
        BigDecimal price;
        try { price = new BigDecimal(value(row,mapping,"price")); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("价格必须是有效数字"); }
        if (price.signum()<=0 || price.scale()>2 || price.compareTo(new BigDecimal("99999999.99"))>0) throw new IllegalArgumentException("价格须大于 0、不超过 99999999.99，且最多两位小数");
        int stock;
        try {
            String raw = value(row,mapping,"stock");
            if (!raw.matches("[0-9]{1,10}")) throw new NumberFormatException();
            stock=Integer.parseInt(raw);
        } catch (NumberFormatException e) { throw new IllegalArgumentException("库存必须为 0~2147483647 的整数"); }
        return new ProductInput(sku,name,category,price,stock);
    }
    private String value(Map<String,String> row, Map<String,String> mapping,String field) { return row.getOrDefault(mapping.getOrDefault(field,""),"").strip(); }
}
