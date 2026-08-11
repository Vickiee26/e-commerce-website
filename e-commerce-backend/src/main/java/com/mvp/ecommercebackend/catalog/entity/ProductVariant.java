package com.mvp.ecommercebackend.catalog.entity;

import com.mvp.ecommercebackend.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "product_variants")
@Getter
@Setter
public class ProductVariant extends BaseEntity {

    @Column(name = "color", length = 60)
    private String color;

    @Column(name = "size", length = 30)
    private String size;

    @Column(name = "stock_quantity", nullable = false)
    private Integer stockQuantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;
}
