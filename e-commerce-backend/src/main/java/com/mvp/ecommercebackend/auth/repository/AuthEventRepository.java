package com.mvp.ecommercebackend.auth.repository;

import com.mvp.ecommercebackend.auth.entity.AuthEvent;
import com.mvp.ecommercebackend.auth.entity.AuthEventType;
import com.mvp.ecommercebackend.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AuthEventRepository extends JpaRepository<AuthEvent, UUID> {

    List<AuthEvent> findAllByUserOrderByCreatedAtDesc(User user);

    List<AuthEvent> findAllByEventType(AuthEventType eventType);
}
