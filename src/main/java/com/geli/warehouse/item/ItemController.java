package com.geli.warehouse.item;

import com.geli.warehouse.common.dto.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @PostMapping
    public ResponseEntity<ItemResponse> create(@Valid @RequestBody CreateItemRequest request) {
        ItemResponse item = itemService.create(request);
        return ResponseEntity.created(URI.create("/api/items/" + item.id())).body(item);
    }

    @GetMapping
    public PageResponse<ItemSummaryResponse> list(
            @RequestParam(required = false) @Size(max = 150) String name,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return itemService.list(name, page, size);
    }

    @GetMapping("/{id}")
    public ItemResponse get(@PathVariable @Positive long id) {
        return itemService.get(id);
    }

    @PutMapping("/{id}")
    public ItemResponse update(@PathVariable @Positive long id, @Valid @RequestBody UpdateItemRequest request) {
        return itemService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable @Positive long id) {
        itemService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
