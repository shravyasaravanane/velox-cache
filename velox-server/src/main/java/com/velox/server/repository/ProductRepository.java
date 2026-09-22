package com.velox.server.repository;

import com.velox.server.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    @Query("select p from Product p where lower(p.name) like lower(concat('%', :term, '%'))")
    List<Product> searchByName(@Param("term") String term, org.springframework.data.domain.Pageable page);
}
