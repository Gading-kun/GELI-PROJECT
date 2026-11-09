package com.geli.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class WarehouseApiTest extends PostgresIntegrationTest {

    @Test
    void createsReadsUpdatesAndDeletesAnOptionlessItem() throws Exception {
        JsonNode item = createItem("Notebook", "notebook", "25.50", 8);
        long id = item.path("id").asLong();

        assertThat(item.path("name").asText()).isEqualTo("Notebook");
        assertThat(item.path("totalStock").asInt()).isEqualTo(8);
        assertThat(item.path("variants")).hasSize(1);
        JsonNode variant = item.path("variants").get(0);
        assertThat(variant.path("sku").asText()).isEqualTo("NOTEBOOK");
        assertThat(variant.path("size").isNull()).isTrue();
        assertThat(variant.path("color").isNull()).isTrue();
        assertMoney(variant, "effectivePrice", "25.50");

        JsonNode fetched = read(mvc.perform(get("/api/items/{id}", id)).andExpect(status().isOk()));
        assertThat(fetched.path("variants")).hasSize(1);
        JsonNode updated = read(mvc.perform(put("/api/items/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  Hardcover Notebook  ","description":"  Blue cover  ","basePrice":30.00}
                                """))
                .andExpect(status().isOk()));
        assertThat(updated.path("name").asText()).isEqualTo("Hardcover Notebook");
        assertThat(updated.path("description").asText()).isEqualTo("Blue cover");
        assertMoney(updated.path("variants").get(0), "effectivePrice", "30.00");

        mvc.perform(delete("/api/items/{id}", id)).andExpect(status().isNoContent());
        assertProblem(mvc.perform(get("/api/items/{id}", id)), 404, "RESOURCE_NOT_FOUND");
        assertThat(count("items")).isZero();
        assertThat(count("variants")).isZero();
    }

    @Test
    void variantsCanOverrideTheirPriceAndReturnToTheBasePrice() throws Exception {
        JsonNode item = createItem("T-shirt", "shirt-default", "100.00", 5);
        long itemId = item.path("id").asLong();
        JsonNode added = addVariant(itemId, "shirt-red-m", "M", "Red", "125.00", 3);
        long variantId = added.path("id").asLong();

        assertMoney(added, "effectivePrice", "125.00");
        JsonNode page = read(mvc.perform(get("/api/items/{id}/variants", itemId))
                .andExpect(status().isOk()));
        assertThat(page.path("totalElements").asInt()).isEqualTo(2);

        JsonNode updated = read(mvc.perform(put("/api/variants/{id}", variantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"shirt-blue-m","size":"M","color":"Blue","priceOverride":null}
                                """))
                .andExpect(status().isOk()));
        assertThat(updated.path("priceOverride").isNull()).isTrue();
        assertThat(updated.path("sku").asText()).isEqualTo("SHIRT-BLUE-M");
        assertThat(updated.path("stock").asInt()).isEqualTo(3);
        assertMoney(updated, "effectivePrice", "100.00");

        mvc.perform(put("/api/items/{id}", itemId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"T-shirt","basePrice":120.00}
                                """))
                .andExpect(status().isOk());
        JsonNode fetched = read(mvc.perform(get("/api/variants/{id}", variantId))
                .andExpect(status().isOk()));
        assertMoney(fetched, "effectivePrice", "120.00");
        JsonNode details = read(mvc.perform(get("/api/items/{id}", itemId)).andExpect(status().isOk()));
        assertThat(details.path("totalStock").asInt()).isEqualTo(8);

        mvc.perform(delete("/api/variants/{id}", variantId)).andExpect(status().isNoContent());
        assertProblem(mvc.perform(get("/api/variants/{id}", variantId)), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void skuConflictsHaveTheSameMessageOnCreateAndUpdate() throws Exception {
        createItem("First", "mixed-case", "10.00", 1);
        JsonNode createError = assertProblem(mvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody("Duplicate", "MiXeD-CaSe", "10.00", 1))),
                409, "CONFLICT");
        assertThat(createError.path("detail").asText()).isEqualTo("A variant with that SKU already exists.");
        assertThat(count("items")).isEqualTo(1);

        JsonNode other = createItem("Second", "unique", "10.00", 1);
        long variantId = variantId(other);
        JsonNode updateError = assertProblem(mvc.perform(put("/api/variants/{id}", variantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"mixed-case"}
                                """)),
                409, "CONFLICT");
        assertThat(updateError.path("detail").asText()).isEqualTo(createError.path("detail").asText());
        assertThat(count("items")).isEqualTo(2);
        assertThat(count("variants")).isEqualTo(2);
    }

    @Test
    void variantOptionsAreUniqueIgnoringCaseAndBlankValues() throws Exception {
        JsonNode item = createItem("Shirt", "plain", "10.00", 1);
        long itemId = item.path("id").asLong();
        String optionMessage = "A variant with that size and color already exists for this item.";

        JsonNode emptyError = assertProblem(mvc.perform(post("/api/items/{id}/variants", itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"empty-options","size":"  ","color":"","initialStock":0}
                                """)),
                409, "CONFLICT");
        assertThat(emptyError.path("detail").asText()).isEqualTo(optionMessage);

        addVariant(itemId, "red-medium", "M", "Red", "null", 2);
        JsonNode caseError = assertProblem(mvc.perform(post("/api/items/{id}/variants", itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"red-medium-duplicate","size":" m ","color":" rED ","initialStock":0}
                                """)),
                409, "CONFLICT");
        assertThat(caseError.path("detail").asText()).isEqualTo(optionMessage);

        JsonNode updateError = assertProblem(mvc.perform(put("/api/variants/{id}", variantId(item))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"plain","size":"M","color":"red"}
                                """)),
                409, "CONFLICT");
        assertThat(updateError.path("detail").asText()).isEqualTo(optionMessage);
        assertThat(count("variants")).isEqualTo(2);
    }

    @Test
    void anItemKeepsOneVariantUntilTheItemItselfIsDeleted() throws Exception {
        JsonNode item = createItem("Notebook", "plain", "10.00", 1);
        long itemId = item.path("id").asLong();
        JsonNode error = assertProblem(mvc.perform(delete("/api/variants/{id}", variantId(item))),
                409, "CONFLICT");
        assertThat(error.path("detail").asText())
                .isEqualTo("An item must keep at least one variant. Delete the item instead.");

        JsonNode added = addVariant(itemId, "blue", "A5", "Blue", "null", 0);
        mvc.perform(delete("/api/variants/{id}", variantId(item))).andExpect(status().isNoContent());
        assertProblem(mvc.perform(delete("/api/variants/{id}", added.path("id").asLong())),
                409, "CONFLICT");
        mvc.perform(delete("/api/items/{id}", itemId)).andExpect(status().isNoContent());
        assertThat(count("variants")).isZero();
    }

    @Test
    void aSaleKeepsItsOriginalPriceSkuAndItemName() throws Exception {
        JsonNode item = createItem("Notebook", "original-sku", "25.50", 5);
        long itemId = item.path("id").asLong();
        long variantId = variantId(item);
        JsonNode sale = sell(variantId, 2);
        long saleId = sale.path("id").asLong();

        assertMoney(sale, "unitPrice", "25.50");
        assertMoney(sale, "totalAmount", "51.00");
        assertThat(sale.path("quantity").asInt()).isEqualTo(2);
        assertThat(sale.path("sku").asText()).isEqualTo("ORIGINAL-SKU");
        assertThat(sale.path("createdAt").asText()).isNotBlank();

        mvc.perform(put("/api/items/{id}", itemId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Renamed Notebook","basePrice":99.00}
                                """))
                .andExpect(status().isOk());
        mvc.perform(put("/api/variants/{id}", variantId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"replacement-sku","priceOverride":150.00}
                                """))
                .andExpect(status().isOk());

        JsonNode saved = read(mvc.perform(get("/api/sales/{id}", saleId)).andExpect(status().isOk()));
        assertThat(saved.path("itemName").asText()).isEqualTo("Notebook");
        assertThat(saved.path("sku").asText()).isEqualTo("ORIGINAL-SKU");
        assertMoney(saved, "unitPrice", "25.50");
        assertMoney(saved, "totalAmount", "51.00");
        assertThat(stock(variantId)).isEqualTo(3);
        assertThat(count("sales")).isEqualTo(1);
    }

    @Test
    void saleHistoryPreventsDeletingTheItemOrVariant() throws Exception {
        JsonNode item = createItem("Notebook", "notebook", "10.00", 2);
        long itemId = item.path("id").asLong();
        long variantId = variantId(item);
        addVariant(itemId, "spare", "A5", "Blue", "null", 1);
        sell(variantId, 1);

        JsonNode variantError = assertProblem(mvc.perform(delete("/api/variants/{id}", variantId)),
                409, "CONFLICT");
        assertThat(variantError.path("detail").asText()).isEqualTo("Variants with sale history cannot be deleted.");
        JsonNode itemError = assertProblem(mvc.perform(delete("/api/items/{id}", itemId)),
                409, "CONFLICT");
        assertThat(itemError.path("detail").asText()).isEqualTo("Items with sale history cannot be deleted.");
        assertThat(count("items")).isEqualTo(1);
        assertThat(count("variants")).isEqualTo(2);
        assertThat(count("sales")).isEqualTo(1);
    }

    @Test
    void insufficientStockDoesNotChangeInventoryOrCreateASale() throws Exception {
        JsonNode item = createItem("Notebook", "notebook", "10.00", 5);
        long variantId = variantId(item);
        JsonNode error = assertProblem(saleRequest(variantId, "6"), 409, "CONFLICT");
        assertThat(error.path("detail").asText()).isEqualTo("Insufficient stock for variant " + variantId
                + ": requested 6, available 5.");
        assertThat(stock(variantId)).isEqualTo(5);
        assertThat(count("sales")).isZero();

        sell(variantId, 5);
        assertThat(stock(variantId)).isZero();
        assertProblem(saleRequest(variantId, "1"), 409, "CONFLICT");
        assertThat(stock(variantId)).isZero();
        assertThat(count("sales")).isEqualTo(1);
    }

    @Test
    void restockingAddsToExistingStock() throws Exception {
        long variantId = variantId(createItem("Notebook", "notebook", "10.00", 3));
        JsonNode response = read(mvc.perform(post("/api/variants/{id}/restocks", variantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":7}
                                """))
                .andExpect(status().isOk()));
        assertThat(response.path("stock").asInt()).isEqualTo(10);
        sell(variantId, 10);
        assertThat(stock(variantId)).isZero();
    }

    @Test
    void restockOverflowIsRejectedWithoutChangingStock() throws Exception {
        long variantId = variantId(createItem("Notebook", "notebook", "10.00", Integer.MAX_VALUE));
        JsonNode error = assertProblem(mvc.perform(post("/api/variants/{id}/restocks", variantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """)),
                409, "CONFLICT");
        assertThat(error.path("detail").asText()).isEqualTo("Restock would exceed the maximum stock quantity.");
        assertThat(stock(variantId)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void saleAndRestockQuantitiesMustBePositiveIntegers() throws Exception {
        long variantId = variantId(createItem("Notebook", "notebook", "10.00", 5));
        for (String quantity : List.of("0", "-1", "null")) {
            assertProblem(saleRequest(variantId, quantity), 400, "VALIDATION_ERROR");
            assertProblem(mvc.perform(post("/api/variants/{id}/restocks", variantId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\":" + quantity + "}")),
                    400, "VALIDATION_ERROR");
        }
        assertProblem(mvc.perform(post("/api/variants/{id}/restocks", variantId)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")),
                400, "VALIDATION_ERROR");
        for (String quantity : List.of("1.5", "2147483648", "\"many\"")) {
            assertProblem(saleRequest(variantId, quantity), 400, "MALFORMED_REQUEST");
            assertProblem(mvc.perform(post("/api/variants/{id}/restocks", variantId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\":" + quantity + "}")),
                    400, "MALFORMED_REQUEST");
        }
        assertThat(stock(variantId)).isEqualTo(5);
        assertThat(count("sales")).isZero();
    }

    @Test
    void invalidPricesAreRejectedBeforeAnythingIsPersisted() throws Exception {
        for (String price : List.of("-0.01", "1.001", "100000000000000000.00", "null")) {
            JsonNode error = assertProblem(mvc.perform(post("/api/items")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(itemBody("Notebook", "notebook", price, 1))),
                    400, "VALIDATION_ERROR");
            assertThat(error.path("errors").findValuesAsText("field")).contains("basePrice");
        }
        assertThat(count("items")).isZero();
        assertThat(count("variants")).isZero();
    }

    @Test
    void initialStockRejectsNegativeNullAndFractionalValues() throws Exception {
        for (String value : List.of("-1", "null", "0.5", "2147483648")) {
            String code = "-1".equals(value) || "null".equals(value)
                    ? "VALIDATION_ERROR" : "MALFORMED_REQUEST";
            assertProblem(mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Notebook","basePrice":10,"variants":[{"sku":"notebook","initialStock":%s}]}
                                    """.formatted(value))),
                    400, code);
        }
        assertThat(count("items")).isZero();
    }

    @Test
    void initialStockIsRequiredForEveryVariant() throws Exception {
        JsonNode error = assertProblem(mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Notebook","basePrice":10,"variants":[{"sku":"notebook"}]}
                                """)),
                400, "VALIDATION_ERROR");
        assertThat(error.path("errors").findValuesAsText("field")).contains("variants[0].initialStock");
        assertThat(count("items")).isZero();
        assertThat(count("variants")).isZero();
    }

    @Test
    void zeroPriceAndMaximumStockAreSupported() throws Exception {
        long variantId = variantId(createItem("Free sample", "sample", "0.00", Integer.MAX_VALUE));
        JsonNode sale = sell(variantId, Integer.MAX_VALUE);
        assertMoney(sale, "unitPrice", "0.00");
        assertMoney(sale, "totalAmount", "0.00");
        assertThat(stock(variantId)).isZero();
    }

    @Test
    void aSaleTotalThatCannotBeStoredIsRejectedBeforeStockChanges() throws Exception {
        long variantId = variantId(createItem("Rare item", "rare", "99999999999999999.99", 2));
        JsonNode error = assertProblem(saleRequest(variantId, "2"), 400, "INVALID_REQUEST");
        assertThat(error.path("detail").asText()).isEqualTo("Sale total exceeds the supported amount.");
        assertThat(stock(variantId)).isEqualTo(2);
        assertThat(count("sales")).isZero();
    }

    @Test
    void missingResourcesReturnConsistentNotFoundProblems() throws Exception {
        for (String path : List.of("/api/items/999", "/api/variants/999", "/api/sales/999",
                "/api/items/999/variants")) {
            assertProblem(mvc.perform(get(path)), 404, "RESOURCE_NOT_FOUND");
        }
        assertProblem(mvc.perform(post("/api/variants/999/restocks")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":1}")),
                404, "RESOURCE_NOT_FOUND");
        assertProblem(saleRequest(999, "1"), 404, "RESOURCE_NOT_FOUND");
        assertThat(count("sales")).isZero();
    }

    @Test
    void pageSizeAndPathParametersAreValidatedConsistently() throws Exception {
        long itemId = createItem("Notebook", "notebook", "10.00", 1).path("id").asLong();
        for (String path : List.of("/api/items", "/api/sales", "/api/items/" + itemId + "/variants")) {
            for (String query : List.of("?page=-1", "?size=0", "?size=101")) {
                assertProblem(mvc.perform(get(path + query)), 400, "VALIDATION_ERROR");
            }
            assertProblem(mvc.perform(get(path + "?page=abc")), 400, "INVALID_REQUEST");
        }
        for (String path : List.of("/api/items", "/api/variants", "/api/sales")) {
            assertProblem(mvc.perform(get(path + "/0")), 400, "VALIDATION_ERROR");
            assertProblem(mvc.perform(get(path + "/-1")), 400, "VALIDATION_ERROR");
            assertProblem(mvc.perform(get(path + "/abc")), 400, "INVALID_REQUEST");
        }
    }

    @Test
    void itemCreationRequiresANameAndAtLeastOneValidVariant() throws Exception {
        for (String body : List.of(
                """
                        {"name":" ","basePrice":10,"variants":[{"sku":"valid","initialStock":0}]}
                        """,
                """
                        {"name":"Notebook","basePrice":10,"variants":[]}
                        """,
                """
                        {"name":"Notebook","basePrice":10,"variants":[null]}
                        """,
                """
                        {"name":"Notebook","basePrice":10,"variants":[{"sku":"invalid sku","initialStock":0}]}
                        """)) {
            assertProblem(mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON).content(body)),
                    400, "VALIDATION_ERROR");
        }
        assertThat(count("items")).isZero();
    }

    @Test
    void unknownFieldsAndStockOverwritesAreRejected() throws Exception {
        JsonNode item = createItem("Notebook", "notebook", "10.00", 5);
        long variantId = variantId(item);
        assertProblem(mvc.perform(put("/api/variants/{id}", variantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"notebook","stock":999}
                                """)),
                400, "MALFORMED_REQUEST");
        assertProblem(mvc.perform(put("/api/items/{id}", item.path("id").asLong())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Notebook","basePrice":10,"unexpected":true}
                                """)),
                400, "MALFORMED_REQUEST");
        assertProblem(mvc.perform(post("/api/sales").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":" + variantId + ",\"quantity\":1,\"unitPrice\":0}")),
                400, "MALFORMED_REQUEST");
        assertThat(stock(variantId)).isEqualTo(5);
        assertThat(count("sales")).isZero();
    }

    @Test
    void malformedMissingAndTrailingJsonBodiesReturnTheSameProblemShape() throws Exception {
        for (String body : List.of("", "{", "{} {}")) {
            assertProblem(mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON).content(body)),
                    400, "MALFORMED_REQUEST");
        }
    }

    @Test
    void unsupportedRoutesMethodsAndContentTypesUseProblemDetails() throws Exception {
        assertProblem(mvc.perform(get("/api/unknown")), 404, "RESOURCE_NOT_FOUND");
        assertProblem(mvc.perform(patch("/api/items")), 405, "METHOD_NOT_ALLOWED");
        assertProblem(mvc.perform(post("/api/items").contentType(MediaType.TEXT_PLAIN).content("hello")),
                415, "UNSUPPORTED_MEDIA_TYPE");
        assertProblem(mvc.perform(get("/api/items").accept(MediaType.TEXT_PLAIN)), 406, "NOT_ACCEPTABLE");
    }

    @Test
    void itemAndSalePaginationHaveStableOrdering() throws Exception {
        JsonNode first = createItem("Red notebook", "first", "10.00", 3);
        JsonNode second = createItem("Blue notebook", "second", "20.00", 3);
        createItem("Pencil", "third", "5.00", 3);

        JsonNode items = read(mvc.perform(get("/api/items?name=NOTEBOOK&page=1&size=1"))
                .andExpect(status().isOk()));
        assertThat(items.path("totalElements").asInt()).isEqualTo(2);
        assertThat(items.path("totalPages").asInt()).isEqualTo(2);
        assertThat(items.path("content").get(0).path("id").asLong()).isEqualTo(second.path("id").asLong());

        JsonNode firstSale = sell(variantId(first), 1);
        JsonNode secondSale = sell(variantId(second), 1);
        JsonNode sales = read(mvc.perform(get("/api/sales?page=0&size=1")).andExpect(status().isOk()));
        assertThat(sales.path("totalElements").asInt()).isEqualTo(2);
        assertThat(sales.path("content").get(0).path("id").asLong()).isEqualTo(secondSale.path("id").asLong());
        JsonNode next = read(mvc.perform(get("/api/sales?page=1&size=1")).andExpect(status().isOk()));
        assertThat(next.path("content").get(0).path("id").asLong()).isEqualTo(firstSale.path("id").asLong());
    }

    @Test
    void anInvalidLaterVariantRollsBackTheEntireItemCreation() throws Exception {
        JsonNode error = assertProblem(mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Shirt",
                                  "basePrice":10.00,
                                  "variants":[
                                    {"sku":"duplicate","size":"M","color":"Red","initialStock":4},
                                    {"sku":"DUPLICATE","size":"L","color":"Blue","initialStock":2}
                                  ]
                                }
                                """)),
                409, "CONFLICT");
        assertThat(error.path("detail").asText()).isEqualTo("A variant with that SKU already exists.");
        assertThat(count("items")).isZero();
        assertThat(count("variants")).isZero();
    }

    private JsonNode createItem(String name, String sku, String price, int stock) throws Exception {
        return read(mvc.perform(post("/api/items").contentType(MediaType.APPLICATION_JSON)
                        .content(itemBody(name, sku, price, stock)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location")));
    }

    private String itemBody(String name, String sku, String price, int stock) {
        return """
                {"name":"%s","basePrice":%s,"variants":[{"sku":"%s","initialStock":%d}]}
                """.formatted(name, price, sku, stock);
    }

    private JsonNode addVariant(long itemId, String sku, String size, String color,
                                String priceOverride, int stock) throws Exception {
        return read(mvc.perform(post("/api/items/{id}/variants", itemId).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sku":"%s","size":"%s","color":"%s","priceOverride":%s,"initialStock":%d}
                                """.formatted(sku, size, color, priceOverride, stock)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location")));
    }

    private JsonNode sell(long variantId, int quantity) throws Exception {
        return read(saleRequest(variantId, Integer.toString(quantity))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location")));
    }

    private ResultActions saleRequest(long variantId, String quantity) throws Exception {
        return mvc.perform(post("/api/sales").contentType(MediaType.APPLICATION_JSON)
                .content("{\"variantId\":" + variantId + ",\"quantity\":" + quantity + "}"));
    }

    private JsonNode assertProblem(ResultActions actions, int expectedStatus, String code) throws Exception {
        var response = actions.andExpect(status().is(expectedStatus)).andReturn().getResponse();
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertThat(body.path("status").asInt()).isEqualTo(expectedStatus);
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("type").asText()).startsWith("urn:geli:problem:");
        assertThat(body.path("title").asText()).isNotBlank();
        assertThat(body.path("detail").asText()).isNotBlank();
        assertThat(body.path("instance").asText()).startsWith("/api/");
        assertThat(body.path("errors").isArray()).isTrue();
        assertThat(body.has("trace")).isFalse();
        return body;
    }

    private JsonNode read(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private long variantId(JsonNode item) {
        return item.path("variants").get(0).path("id").asLong();
    }

    private int stock(long variantId) {
        return jdbc.queryForObject("select stock from variants where id = ?", Integer.class, variantId);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private void assertMoney(JsonNode body, String field, String expected) {
        assertThat(body.path(field).decimalValue()).isEqualByComparingTo(new BigDecimal(expected));
    }
}
