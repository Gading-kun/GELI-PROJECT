package com.geli.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.geli.warehouse.common.error.ConflictException;
import com.geli.warehouse.common.error.ResourceNotFoundException;
import com.geli.warehouse.item.CreateItemRequest;
import com.geli.warehouse.item.ItemRepository;
import com.geli.warehouse.item.ItemResponse;
import com.geli.warehouse.item.ItemService;
import com.geli.warehouse.sale.CreateSaleRequest;
import com.geli.warehouse.sale.SaleResponse;
import com.geli.warehouse.sale.SaleService;
import com.geli.warehouse.variant.CreateVariantRequest;
import com.geli.warehouse.variant.RestockRequest;
import com.geli.warehouse.variant.UpdateVariantRequest;
import com.geli.warehouse.variant.VariantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class WarehouseConcurrencyTest extends PostgresIntegrationTest {

    @Autowired
    private ItemService itemService;

    @Autowired
    private VariantService variantService;

    @Autowired
    private SaleService saleService;

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void onlyOneBuyerCanPurchaseTheLastUnit() throws Exception {
        long variantId = createItem(1).variants().getFirst().id();
        List<Callable<Boolean>> buyers = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            buyers.add(() -> purchase(variantId, 1));
        }

        assertThat(runTogether(buyers)).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(variantService.get(variantId).stock()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sales", Integer.class)).isEqualTo(1);
    }

    @Test
    void competingSalesNeverDeductMoreThanAvailableStock() throws Exception {
        long variantId = createItem(25).variants().getFirst().id();
        List<Callable<Boolean>> buyers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            buyers.add(() -> purchase(variantId, 2));
        }

        assertThat(runTogether(buyers)).filteredOn(Boolean::booleanValue).hasSize(12);
        assertThat(variantService.get(variantId).stock()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select sum(quantity) from sales", Long.class)).isEqualTo(24);
    }

    @Test
    void restocksSalesAndMetadataEditsDoNotLoseStockUpdates() throws Exception {
        ItemResponse item = createItem(50);
        long variantId = item.variants().getFirst().id();
        List<Callable<Boolean>> operations = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            operations.add(() -> purchase(variantId, 2));
            operations.add(() -> {
                variantService.restock(variantId, new RestockRequest(3));
                return true;
            });
            operations.add(() -> {
                variantService.update(variantId, new UpdateVariantRequest("TEST-SKU", null, null,
                        new BigDecimal("12.00")));
                return true;
            });
        }

        assertThat(runTogether(operations)).containsOnly(true);
        assertThat(variantService.get(variantId).stock()).isEqualTo(58);
        assertThat(jdbc.queryForObject("select sum(quantity) from sales", Long.class)).isEqualTo(16);
        assertThat(jdbc.queryForObject("select count(*) from sales where total_amount <> unit_price * quantity",
                Integer.class)).isZero();
    }

    @Test
    void aSaleWaitsForAnItemPriceUpdateAndUsesTheCommittedPrice() throws Exception {
        ItemResponse item = createItem(5);
        long variantId = item.variants().getFirst().id();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch started = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = transaction.execute(status -> {
                itemRepository.findByIdForUpdate(item.id()).orElseThrow()
                        .updateDetails("Updated product", null, new BigDecimal("35.50"));
                var purchase = executor.submit(() -> {
                    started.countDown();
                    return saleService.create(new CreateSaleRequest(variantId, 1));
                });
                try {
                    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> purchase.get(150, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return purchase;
            });

            SaleResponse sale = pending.get(10, TimeUnit.SECONDS);
            assertThat(sale.unitPrice()).isEqualByComparingTo("35.50");
            assertThat(sale.itemName()).isEqualTo("Updated product");
        }

        assertThat(variantService.get(variantId).stock()).isEqualTo(4);
    }

    @Test
    void competingVariantDeletesKeepAtLeastOneVariant() throws Exception {
        ItemResponse item = createItem(1);
        long first = item.variants().getFirst().id();
        long second = variantService.create(item.id(),
                new CreateVariantRequest("SECOND-SKU", "L", "Blue", null, 1)).id();

        List<Boolean> results = runTogether(List.of(() -> deleteVariant(first), () -> deleteVariant(second)));

        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(itemService.get(item.id()).variants()).hasSize(1);
    }

    @Test
    void concurrentDuplicateOptionsCreateOnlyOneVariant() throws Exception {
        ItemResponse item = createItem(1);
        List<Callable<Boolean>> additions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String sku = "NEW-SKU-" + i;
            additions.add(() -> {
                try {
                    variantService.create(item.id(), new CreateVariantRequest(sku, "L", "Blue", null, 0));
                    return true;
                } catch (ConflictException exception) {
                    return false;
                }
            });
        }

        assertThat(runTogether(additions)).filteredOn(Boolean::booleanValue).hasSize(1);
        assertThat(itemService.get(item.id()).variants()).hasSize(2);
    }

    @Test
    void aSaleAndItemDeletionHaveOnlyConsistentOutcomes() throws Exception {
        ItemResponse item = createItem(1);
        long variantId = item.variants().getFirst().id();

        List<String> results = runTogether(List.of(() -> {
            try {
                saleService.create(new CreateSaleRequest(variantId, 1));
                return "sold";
            } catch (ResourceNotFoundException exception) {
                return "missing";
            }
        }, () -> {
            try {
                itemService.delete(item.id());
                return "deleted";
            } catch (ConflictException exception) {
                return "retained";
            }
        }));

        if (results.contains("sold")) {
            assertThat(results).containsExactly("sold", "retained");
            assertThat(variantService.get(variantId).stock()).isZero();
            assertThat(jdbc.queryForObject("select count(*) from sales", Integer.class)).isEqualTo(1);
        } else {
            assertThat(results).containsExactly("missing", "deleted");
            assertThat(jdbc.queryForObject("select count(*) from variants", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from sales", Integer.class)).isZero();
        }
    }

    @Test
    void aDatabaseFailureDuringSaleCreationRollsBackTheOperation() {
        long variantId = createItem(5).variants().getFirst().id();
        jdbc.execute("""
                CREATE FUNCTION reject_test_sale() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'Sale write rejected for rollback test';
                END;
                $$
                """);
        jdbc.execute("CREATE TRIGGER reject_sale BEFORE INSERT ON sales FOR EACH ROW EXECUTE FUNCTION reject_test_sale()");
        try {
            assertThatThrownBy(() -> saleService.create(new CreateSaleRequest(variantId, 2)))
                    .isInstanceOf(DataAccessException.class);
            assertThat(variantService.get(variantId).stock()).isEqualTo(5);
            assertThat(jdbc.queryForObject("select count(*) from sales", Integer.class)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_sale ON sales");
            jdbc.execute("DROP FUNCTION reject_test_sale()");
        }
    }

    @Test
    void concurrentItemCreationWithTheSameSkuRollsBackTheLosingItem() throws Exception {
        String request = """
                {"name":"Concurrent product","basePrice":10.00,
                 "variants":[{"sku":"SHARED-SKU","initialStock":1}]}
                """;
        Callable<Integer> create = () -> mvc.perform(post("/api/items")
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andReturn().getResponse().getStatus();

        assertThat(runTogether(List.of(create, create))).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("select count(*) from items", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from variants", Integer.class)).isEqualTo(1);
    }

    @Test
    void aLockTimeoutReturnsAConsistentRetryableError() throws Exception {
        ItemResponse item = createItem(1);
        long variantId = item.variants().getFirst().id();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (var executor = Executors.newSingleThreadExecutor()) {
            transaction.executeWithoutResult(status -> {
                itemRepository.findByIdForUpdate(item.id()).orElseThrow();
                var pending = executor.submit(() -> mvc.perform(post("/api/sales")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"variantId\":" + variantId + ",\"quantity\":1}"))
                        .andReturn().getResponse());
                try {
                    var response = pending.get(10, TimeUnit.SECONDS);
                    assertThat(response.getStatus()).isEqualTo(503);
                    assertThat(response.getHeader("Retry-After")).isEqualTo("1");
                    var problem = objectMapper.readTree(response.getContentAsString());
                    assertThat(problem.path("code").asText()).isEqualTo("RESOURCE_BUSY");
                    assertThat(problem.path("errors").isArray()).isTrue();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }

        assertThat(variantService.get(variantId).stock()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sales", Integer.class)).isZero();
    }

    private ItemResponse createItem(int stock) {
        return itemService.create(new CreateItemRequest("Test product", null, new BigDecimal("10.00"),
                List.of(new CreateVariantRequest("TEST-SKU", null, null, null, stock))));
    }

    private boolean purchase(long variantId, int quantity) {
        try {
            saleService.create(new CreateSaleRequest(variantId, quantity));
            return true;
        } catch (ConflictException exception) {
            return false;
        }
    }

    private boolean deleteVariant(long id) {
        try {
            variantService.delete(id);
            return true;
        } catch (ConflictException exception) {
            return false;
        }
    }

    private <T> List<T> runTogether(List<Callable<T>> operations) throws Exception {
        CountDownLatch ready = new CountDownLatch(operations.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(operations.size())) {
            var futures = operations.stream().map(operation -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent operations did not start in time.");
                }
                return operation.call();
            })).toList();
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            List<T> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        }
    }
}
