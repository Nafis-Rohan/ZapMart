package com.nafis.ZapMart.cart;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CartRepository extends JpaRepository<Cart, Long> {

    Optional<Cart> findByUserId(Long userId);

    @Query("SELECT DISTINCT c FROM Cart c " +
           "LEFT JOIN FETCH c.items i " +
           "LEFT JOIN FETCH i.product " +
           "WHERE c.userId = :userId")
    Optional<Cart> findByUserIdWithItems(@Param("userId") Long userId);
}