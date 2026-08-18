package com.mvp.ecommercebackend.admin.repository;

import com.mvp.ecommercebackend.admin.entity.AdminEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AdminEventRepository extends JpaRepository<AdminEvent, UUID>,
        JpaSpecificationExecutor<AdminEvent> {
}
