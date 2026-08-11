package com.mvp.ecommercebackend.repositories;

import com.mvp.ecommercebackend.entities.Product;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductRepository extends CrudRepository<Product, String> {
    List<Product> findAllByCategory(String category);
}
