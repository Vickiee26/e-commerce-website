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
@Table(name = "category_types")
@Getter
@Setter
public class CategoryType extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "description", length = 2000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;
}
