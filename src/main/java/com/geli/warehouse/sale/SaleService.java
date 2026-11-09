package com.geli.warehouse.sale;

import com.geli.warehouse.common.dto.PageResponse;
import com.geli.warehouse.common.error.ConflictException;
import com.geli.warehouse.common.error.InvalidRequestException;
import com.geli.warehouse.common.error.ResourceNotFoundException;
import com.geli.warehouse.inventory.InventoryLock;
import com.geli.warehouse.variant.Variant;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SaleService {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999999999.99");

    private final SaleRepository saleRepository;
    private final InventoryLock inventoryLock;
    private final Clock clock;

    public SaleService(SaleRepository saleRepository, InventoryLock inventoryLock, Clock clock) {
        this.saleRepository = saleRepository;
        this.inventoryLock = inventoryLock;
        this.clock = clock;
    }

    @Transactional
    public SaleResponse create(CreateSaleRequest request) {
        Variant variant = inventoryLock.lockVariant(request.variantId());
        int quantity = request.quantity();

        if (variant.getStock() < quantity) {
            throw new ConflictException("Insufficient stock for variant " + variant.getId()
                    + ": requested " + quantity + ", available " + variant.getStock() + ".");
        }

        BigDecimal unitPrice = variant.getEffectivePrice();
        BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));

        if (totalAmount.compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidRequestException("Sale total exceeds the supported amount.");
        }

        variant.decreaseStock(quantity);
        Sale sale = new Sale(variant, quantity, unitPrice, totalAmount, Instant.now(clock));
        return SaleResponse.from(saleRepository.save(sale));
    }

    public SaleResponse get(long id) {
        Sale sale = saleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale", id));
        return SaleResponse.from(sale);
    }

    public PageResponse<SaleResponse> list(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        return PageResponse.from(saleRepository.findAll(pageRequest).map(SaleResponse::from));
    }
}
