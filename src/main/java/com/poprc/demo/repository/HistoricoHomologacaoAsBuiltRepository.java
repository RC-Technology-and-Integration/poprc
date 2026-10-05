package com.poprc.demo.repository;

import com.poprc.demo.model.HistoricoHomologacaoAsBuilt;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HistoricoHomologacaoAsBuiltRepository extends JpaRepository<HistoricoHomologacaoAsBuilt, Long> {
    List<HistoricoHomologacaoAsBuilt> findByComarcaIdOrderByIdDesc(Long comarcaId);
}
