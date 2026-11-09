package com.geli.warehouse.item;

import com.geli.warehouse.common.dto.PageResponse;
import com.geli.warehouse.common.error.ConflictException;
import com.geli.warehouse.common.error.ResourceNotFoundException;
import com.geli.warehouse.variant.VariantRepository;
import com.geli.warehouse.variant.VariantResponse;
import com.geli.warehouse.variant.VariantService;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ItemService {

    private final ItemRepository itemRepository;
    private final VariantRepository variantRepository;
    private final VariantService variantService;

    public ItemService(
            ItemRepository itemRepository,
            VariantRepository variantRepository,
            VariantService variantService) {
        this.itemRepository = itemRepository;
        this.variantRepository = variantRepository;
        this.variantService = variantService;
    }

    @Transactional
    public ItemResponse create(CreateItemRequest request) {
        Item item = new Item(request.name().strip(), cleanText(request.description()), request.basePrice());
        itemRepository.saveAndFlush(item);
        List<VariantResponse> variants = request.variants().stream()
                .map(variant -> variantService.create(item.getId(), variant))
                .toList();
        return ItemResponse.from(item, variants);
    }

    public PageResponse<ItemSummaryResponse> list(String name, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        Page<Item> items = name == null || name.isBlank()
                ? itemRepository.findAll(pageable)
                : itemRepository.findByNameContainingIgnoreCase(name.strip(), pageable);
        return PageResponse.from(items.map(ItemSummaryResponse::from));
    }

    public ItemResponse get(long id) {
        Item item = itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item", id));
        return responseFor(item);
    }

    @Transactional
    public ItemResponse update(long id, UpdateItemRequest request) {
        Item item = itemRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item", id));
        item.updateDetails(request.name().strip(), cleanText(request.description()), request.basePrice());
        itemRepository.flush();
        return responseFor(item);
    }

    @Transactional
    public void delete(long id) {
        Item item = itemRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item", id));
        if (variantRepository.hasSalesForItem(id)) {
            throw new ConflictException("Items with sale history cannot be deleted.");
        }
        variantRepository.deleteByItemId(id);
        itemRepository.delete(item);
        itemRepository.flush();
    }

    private ItemResponse responseFor(Item item) {
        List<VariantResponse> variants = variantRepository.findByItemIdOrderByIdAsc(item.getId()).stream()
                .map(VariantResponse::from)
                .toList();
        return ItemResponse.from(item, variants);
    }

    private String cleanText(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
