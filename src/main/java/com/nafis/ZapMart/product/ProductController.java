package com.nafis.ZapMart.product;

import com.nafis.ZapMart.common.security.AdminGuard;
import com.nafis.ZapMart.product.dto.ProductRequest;
import com.nafis.ZapMart.product.dto.ProductResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final AdminGuard adminGuard;

    @GetMapping
    public Page<ProductResponse> list(@RequestParam(required = false) String name,
                                      @RequestParam(required = false) String category,
                                      Pageable pageable) {
        return productService.list(name, category, pageable);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return productService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse create(@RequestHeader("X-User-Id") Long userId,
                                  @Valid @RequestBody ProductRequest request) {
        adminGuard.requireAdmin(userId);
        return productService.create(request);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@RequestHeader("X-User-Id") Long userId,
                                  @PathVariable Long id,
                                  @Valid @RequestBody ProductRequest request) {
        adminGuard.requireAdmin(userId);
        return productService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        adminGuard.requireAdmin(userId);
        productService.delete(id);
    }
}