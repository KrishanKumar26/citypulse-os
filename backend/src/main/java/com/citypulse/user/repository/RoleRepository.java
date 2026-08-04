package com.citypulse.user.repository;

import com.citypulse.user.domain.Role;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {

    @EntityGraph(attributePaths = {"permissions"})
    Optional<Role> findByNameAndDeletedAtIsNull(String name);

    @EntityGraph(attributePaths = {"permissions"})
    List<Role> findByNameInAndDeletedAtIsNull(Set<String> names);

    @EntityGraph(attributePaths = {"permissions"})
    List<Role> findByDeletedAtIsNullOrderByNameAsc();
}
