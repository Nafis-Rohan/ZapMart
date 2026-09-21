package com.nafis.ZapMart.product;

import com.nafis.ZapMart.common.exception.ResourceNotFoundException;
import com.nafis.ZapMart.product.dto.ProductRequest;
import com.nafis.ZapMart.product.dto.ProductResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    private final Pageable pageable = PageRequest.of(0, 10);

    private Product product(String name, String category) {
        Product p = new Product();
        p.setName(name);
        p.setDescription("desc");
        p.setPrice(new BigDecimal("0.25"));
        p.setStockQuantity(100);
        p.setCategory(category);
        return p;
    }

    private ProductRequest request(String name) {
        return new ProductRequest(name, "desc", new BigDecimal("1.50"), 10, "Resistors");
    }

    @Test
    void get_returnsProduct_whenFound() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product("Resistor", "Resistors")));

        ProductResponse response = productService.get(1L);

        assertEquals("Resistor", response.name());
    }

    @Test
    void get_throws_whenNotFound() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> productService.get(999L));
    }

    @Test
    void create_savesAndReturnsProduct() {
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductResponse response = productService.create(request("Capacitor"));

        assertEquals("Capacitor", response.name());
        assertEquals(new BigDecimal("1.50"), response.price());
        verify(productRepository).save(any(Product.class));
    }

    @Test
    void update_changesFields_whenFound() {
        Product existing = product("Old", "Resistors");
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductResponse response = productService.update(1L, request("New"));

        assertEquals("New", response.name());
        assertEquals(10, response.stockQuantity());
    }

    @Test
    void update_throws_whenNotFound() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> productService.update(999L, request("X")));
        verify(productRepository, never()).save(any());
    }

    @Test
    void delete_throws_whenNotFound() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> productService.delete(999L));
        verify(productRepository, never()).delete(any());
    }

    @Test
    void delete_removesProduct_whenFound() {
        Product existing = product("Resistor", "Resistors");
        when(productRepository.findById(1L)).thenReturn(Optional.of(existing));

        productService.delete(1L);

        verify(productRepository).delete(existing);
    }

    @Test
    void list_usesNameSearch_whenNameGiven() {
        Page<Product> page = new PageImpl<>(List.of(product("Resistor", "Resistors")));
        when(productRepository.findByNameContainingIgnoreCase("resis", pageable)).thenReturn(page);

        Page<ProductResponse> result = productService.list("resis", null, pageable);

        assertEquals(1, result.getTotalElements());
        verify(productRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void list_usesCategoryFilter_whenOnlyCategoryGiven() {
        Page<Product> page = new PageImpl<>(List.of(product("Resistor", "Resistors")));
        when(productRepository.findByCategoryIgnoreCase("Resistors", pageable)).thenReturn(page);

        Page<ProductResponse> result = productService.list(null, "Resistors", pageable);

        assertEquals(1, result.getTotalElements());
    }

    @Test
    void list_returnsAll_whenNoFilters() {
        Page<Product> page = new PageImpl<>(List.of(product("A", "X"), product("B", "Y")));
        when(productRepository.findAll(pageable)).thenReturn(page);

        Page<ProductResponse> result = productService.list(null, null, pageable);

        assertEquals(2, result.getTotalElements());
    }
}