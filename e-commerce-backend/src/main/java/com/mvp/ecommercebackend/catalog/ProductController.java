package com.mvp.ecommercebackend.catalog;

import com.mvp.ecommercebackend.catalog.dto.ProductDto;
import com.mvp.ecommercebackend.catalog.dto.ProductSummaryResponse;
import com.mvp.ecommercebackend.common.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Public product browsing. No authentication on any method here, by design: a shopper must be able
 * to see what is for sale before deciding whether to create an account.
 */
@RestController
@RequestMapping("/api/products")
@Tag(name = "Catalog")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * @param sort      restricted to a whitelist rather than passed through, so a caller cannot
     *                  sort by an arbitrary entity path
     * @param direction {@code asc} or {@code desc}, either case
     */
    @GetMapping
    public PageResponse<ProductSummaryResponse> listProducts(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID categoryTypeId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "must not be negative") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "must be at least 1")
            @Max(value = 100, message = "must not exceed 100") int size,
            @RequestParam(defaultValue = "name")
            @Pattern(regexp = "name|price|createdAt",
                    message = "must be one of name, price, createdAt") String sort,
            @RequestParam(defaultValue = "asc")
            @Pattern(regexp = "(?i)asc|desc", message = "must be asc or desc") String direction) {
        return productService.list(categoryId, categoryTypeId, q, page, size, sort, direction);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDto> getProductById(@PathVariable UUID id) {
        return ResponseEntity.ok(productService.getProductById(id));
    }
}
