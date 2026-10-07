package com.poprc.demo.repository;

import com.poprc.demo.model.Comarca;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ComarcaRepository extends JpaRepository<Comarca, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Comarca c where c.id = :id")
    Optional<Comarca> findByIdForUpdate(@Param("id") Long id);

    Optional<Comarca> findByProjetoId(Long projetoId);
    Optional<Comarca> findByOrdemServicoNumeroOs(String numeroOs);
    Optional<Comarca> findByOrdemServicoId(Long ordemServicoId);
    Optional<Comarca> findByNomeComarcaIgnoreCaseAndProjetoContratoId(
            String nomeComarca, Long contratoId);
}
