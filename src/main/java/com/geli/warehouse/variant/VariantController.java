package com.geli.warehouse.variant;

import com.geli.warehouse.common.dto.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class VariantController {

    private final VariantService variantService;

    public VariantController(VariantService variantService) {
        this.variantService = variantService;
    }

    @PostMapping("/api/items/{itemId}/variants")
    public ResponseEntity<VariantResponse> create(
            @PathVariable @Positive long itemId,
            @Valid @RequestBody CreateVariantRequest request) {
        VariantResponse variant = variantService.create(itemId, request);
        return ResponseEntity.created(URI.create("/api/variants/" + variant.id())).body(variant);
    }

    @GetMapping("/api/items/{itemId}/variants")
    public PageResponse<VariantResponse> list(
            @PathVariable @Positive long itemId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return variantService.list(itemId, page, size);
    }

    @GetMapping("/api/variants/{id}")
    public VariantResponse get(@PathVariable @Positive long id) {
        return variantService.get(id);
    }

    @PutMapping("/api/variants/{id}")
    public VariantResponse update(@PathVariable @Positive long id, @Valid @RequestBody UpdateVariantRequest request) {
        return variantService.update(id, request);
    }

    @DeleteMapping("/api/variants/{id}")
    public ResponseEntity<Void> delete(@PathVariable @Positive long id) {
        variantService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/variants/{id}/restocks")
    public VariantResponse restock(@PathVariable @Positive long id, @Valid @RequestBody RestockRequest request) {
        return variantService.restock(id, request);
    }
}
