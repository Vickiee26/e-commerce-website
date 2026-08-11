package com.mvp.ecommercebackend.catalog.entity;

import com.mvp.ecommercebackend.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "categories")
@Getter
@Setter
public class Category extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "description", length = 2000)
    private String description;

    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL)
    private List<CategoryType> categoryTypes = new ArrayList<>();
}
