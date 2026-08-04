package com.citypulse.user.repository;

import com.citypulse.user.domain.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, Long> {

    List<Permission> findByDeletedAtIsNullOrderByNameAsc();
}
