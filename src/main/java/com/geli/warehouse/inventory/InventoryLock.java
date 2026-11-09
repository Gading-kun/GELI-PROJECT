package com.geli.warehouse.inventory;

import com.geli.warehouse.common.error.ResourceNotFoundException;
import com.geli.warehouse.item.ItemRepository;
import com.geli.warehouse.variant.Variant;
import com.geli.warehouse.variant.VariantRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class InventoryLock {

    private final ItemRepository itemRepository;
    private final VariantRepository variantRepository;

    public InventoryLock(ItemRepository itemRepository, VariantRepository variantRepository) {
        this.itemRepository = itemRepository;
        this.variantRepository = variantRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Variant lockVariant(long variantId) {
        long itemId = variantRepository.findItemIdById(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("Variant", variantId));

        itemRepository.findByIdForUpdate(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Variant", variantId));

        return variantRepository.findByIdAndItemIdForUpdate(variantId, itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Variant", variantId));
    }
}
