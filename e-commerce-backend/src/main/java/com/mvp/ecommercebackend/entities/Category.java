package com.mvp.ecommercebackend.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Entity
@Table(name = "categories")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {
    @Id private String id;

    private String name;

    private String code;

    private String description;

    @OneToMany(mappedBy = "category", cascade = CascadeType.ALL)
    private List<CategoryType> categoryTypes;
}
