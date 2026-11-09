package com.geli.warehouse.variant;

import com.geli.warehouse.common.dto.PageResponse;
import com.geli.warehouse.common.error.ConflictException;
import com.geli.warehouse.common.error.ResourceNotFoundException;
import com.geli.warehouse.inventory.InventoryLock;
import com.geli.warehouse.item.Item;
import com.geli.warehouse.item.ItemRepository;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class VariantService {

    private final VariantRepository variantRepository;
    private final ItemRepository itemRepository;
    private final InventoryLock inventoryLock;

    public VariantService(
            VariantRepository variantRepository,
            ItemRepository itemRepository,
            InventoryLock inventoryLock) {
        this.variantRepository = variantRepository;
        this.itemRepository = itemRepository;
        this.inventoryLock = inventoryLock;
    }

    @Transactional
    public VariantResponse create(long itemId, CreateVariantRequest request) {
        Item item = itemRepository.findByIdForUpdate(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item", itemId));
        if (variantRepository.countByItemId(itemId) >= 100) {
            throw new ConflictException("An item cannot have more than 100 variants.");
        }
        String sku = request.sku().strip().toUpperCase(Locale.ROOT);
        String size = cleanText(request.size());
        String color = cleanText(request.color());
        if (variantRepository.existsBySku(sku)) {
            throw new ConflictException("A variant with that SKU already exists.");
        }
        if (variantRepository.existsByOptions(itemId, optionValue(size), optionValue(color))) {
            throw new ConflictException("A variant with that size and color already exists for this item.");
        }
        Variant variant = new Variant(item, sku, size, color, request.priceOverride(), request.initialStock());
        variantRepository.saveAndFlush(variant);
        return VariantResponse.from(variant);
    }

    public PageResponse<VariantResponse> list(long itemId, int page, int size) {
        if (!itemRepository.existsById(itemId)) {
            throw new ResourceNotFoundException("Item", itemId);
        }
        PageRequest pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        return PageResponse.from(variantRepository.findByItemId(itemId, pageable).map(VariantResponse::from));
    }

    public VariantResponse get(long id) {
        Variant variant = variantRepository.findWithItemById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Variant", id));
        return VariantResponse.from(variant);
    }

    @Transactional
    public VariantResponse update(long id, UpdateVariantRequest request) {
        Variant variant = inventoryLock.lockVariant(id);
        String sku = request.sku().strip().toUpperCase(Locale.ROOT);
        String size = cleanText(request.size());
        String color = cleanText(request.color());
        if (variantRepository.existsBySkuAndIdNot(sku, id)) {
            throw new ConflictException("A variant with that SKU already exists.");
        }
        if (variantRepository.existsByOptionsAndIdNot(
                variant.getItem().getId(), optionValue(size), optionValue(color), id)) {
            throw new ConflictException("A variant with that size and color already exists for this item.");
        }
        variant.updateDetails(sku, size, color, request.priceOverride());
        variantRepository.flush();
        return VariantResponse.from(variant);
    }

    @Transactional
    public VariantResponse restock(long id, RestockRequest request) {
        Variant variant = inventoryLock.lockVariant(id);
        variant.restock(request.quantity());
        variantRepository.flush();
        return VariantResponse.from(variant);
    }

    @Transactional
    public void delete(long id) {
        Variant variant = inventoryLock.lockVariant(id);
        if (variantRepository.hasSales(id)) {
            throw new ConflictException("Variants with sale history cannot be deleted.");
        }
        if (variantRepository.countByItemId(variant.getItem().getId()) <= 1) {
            throw new ConflictException("An item must keep at least one variant. Delete the item instead.");
        }
        variantRepository.delete(variant);
        variantRepository.flush();
    }

    private String cleanText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String optionValue(String value) {
        return value == null ? "" : value;
    }
}
