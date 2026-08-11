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
@Table(name = "product_resources")
@Getter
@Setter
public class Resource extends BaseEntity {

    @Column(name = "name")
    private String name;

    @Column(name = "url", nullable = false, length = 1000)
    private String url;

    @Column(name = "type", length = 30)
    private String type;

    @Column(name = "is_primary", nullable = false)
    private Boolean isPrimary;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;
}
