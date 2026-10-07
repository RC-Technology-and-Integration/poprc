package com.poprc.demo.controller;

import com.poprc.demo.dto.ArquivamentoRequest;
import com.poprc.demo.dto.EquipeProjetoRequest;
import com.poprc.demo.model.Projeto;
import com.poprc.demo.model.Contrato;
import com.poprc.demo.model.ProjetoStatus;
import com.poprc.demo.repository.ProjetoRepository;
import com.poprc.demo.repository.ComarcaRepository;
import com.poprc.demo.repository.ContratoRepository;
import com.poprc.demo.repository.FuncionarioRepository;
import com.poprc.demo.service.ComarcaService;
import com.poprc.demo.service.ArquivamentoService;
import com.poprc.demo.service.ProjetoEquipeService;
import com.poprc.demo.security.UsuarioAutenticado;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.Authentication;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/projetos")
public class ProjetoController {

    private final ProjetoRepository projetoRepository;
    private final ComarcaRepository comarcaRepository;
    private final ContratoRepository contratoRepository;
    private final ComarcaService comarcaService;
    private final FuncionarioRepository funcionarioRepository;
    private final ArquivamentoService arquivamentoService;
    private final ProjetoEquipeService projetoEquipeService;

    public ProjetoController(ProjetoRepository projetoRepository, ComarcaRepository comarcaRepository,
            ContratoRepository contratoRepository,
            ComarcaService comarcaService, FuncionarioRepository funcionarioRepository,
            ArquivamentoService arquivamentoService, ProjetoEquipeService projetoEquipeService) {
        this.projetoRepository = projetoRepository;
        this.comarcaRepository = comarcaRepository;
        this.contratoRepository = contratoRepository;
        this.comarcaService = comarcaService;
        this.funcionarioRepository = funcionarioRepository;
        this.arquivamentoService = arquivamentoService;
        this.projetoEquipeService = projetoEquipeService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> salvarProjeto(@RequestBody Projeto projeto) {
        try {
            Map<String, Object> response = new HashMap<>();

            if (projeto.getContrato() == null || projeto.getContrato().getId() == null) {
                response.put("erro", "Contrato ID é obrigatório");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }
            if (projeto.getDataInicio() == null) {
                response.put("erro", "Data de início é obrigatória");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }
            if (projeto.getDataFim() != null && projeto.getDataFim().isBefore(projeto.getDataInicio())) {
                response.put("erro", "Data de fim não pode ser anterior à data de início");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            Optional<Contrato> contrato = contratoRepository.findById(projeto.getContrato().getId());
            if (!contrato.isPresent()) {
                response.put("erro", "Contrato não encontrado");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            if (Boolean.TRUE.equals(contrato.get().getArquivado())) {
                response.put("erro", "Não é possível criar projeto em um contrato arquivado.");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            if (projeto.getStatus() == null) {
                projeto.setStatus(ProjetoStatus.EM_ANDAMENTO);
            }
            if (projeto.getAsBuiltStatus() == null) {
                projeto.setAsBuiltStatus("PENDENTE");
            }
            projeto.setArquivado(false);
            projeto.setArquivadoEm(null);
            projeto.setArquivadoPor(null);
            projeto.setMotivoArquivamento(null);

            projeto.setContrato(contrato.get());
            if (projeto.getResponsavel() != null && projeto.getResponsavel().getId() != null) {
                projeto.setResponsavel(funcionarioRepository.findById(projeto.getResponsavel().getId())
                        .orElseThrow(() -> new IllegalArgumentException("Funcionário responsável não encontrado.")));
            }
            Projeto projetoSalvo = projetoRepository.save(projeto);

            if (projetoSalvo.getResponsavel() != null) {
                EquipeProjetoRequest equipeInicial = new EquipeProjetoRequest();
                EquipeProjetoRequest.MembroRequest membro = new EquipeProjetoRequest.MembroRequest();
                membro.setFuncionarioId(projetoSalvo.getResponsavel().getId());
                membro.setPapel("LIDER_EQUIPE");
                membro.setResponsavelPrincipal(true);
                equipeInicial.setMembros(List.of(membro));
                projetoEquipeService.substituirEquipe(projetoSalvo.getId(), equipeInicial);
            }

            projetoSalvo.setNomeComarcaVinculada(projeto.getNomeComarcaVinculada());
            comarcaService.criarOuVincularComarcaParaProjeto(projetoSalvo);

            response.put("projeto", projetoSalvo);
            response.put("mensagem", "Projeto saved com sucesso");

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (Exception e) {
            Map<String, Object> erro = new HashMap<>();
            erro.put("erro", "Erro ao salvar projeto: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(erro);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Projeto> buscarPorId(@PathVariable Long id) {
        return projetoRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> atualizarProjeto(@PathVariable Long id,
            @RequestBody Projeto dadosAtualizados) {
        Map<String, Object> response = new HashMap<>();

        return projetoRepository.findById(id)
                .map(projeto -> {
                    var dataInicio = dadosAtualizados.getDataInicio() != null
                            ? dadosAtualizados.getDataInicio()
                            : projeto.getDataInicio();
                    var dataFim = dadosAtualizados.getDataFim() != null
                            ? dadosAtualizados.getDataFim()
                            : projeto.getDataFim();
                    if (dataInicio == null) {
                        throw new IllegalArgumentException("Data de início é obrigatória");
                    }
                    if (dataFim != null && dataFim.isBefore(dataInicio)) {
                        throw new IllegalArgumentException(
                                "Data de fim não pode ser anterior à data de início");
                    }
                    projeto.setDataInicio(dataInicio);
                    projeto.setDataFim(dataFim);
                    if (dadosAtualizados.getStatus() != null)
                        projeto.setStatus(dadosAtualizados.getStatus());
                    if (dadosAtualizados.getAsBuiltStatus() != null)
                        projeto.setAsBuiltStatus(dadosAtualizados.getAsBuiltStatus());

                    if (dadosAtualizados.getResponsavel() != null
                            && dadosAtualizados.getResponsavel().getId() != null) {
                        projeto.setResponsavel(funcionarioRepository
                                .findById(dadosAtualizados.getResponsavel().getId())
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "Funcionário responsável não encontrado.")));
                    }

                    if (dadosAtualizados.getContrato() != null && dadosAtualizados.getContrato().getId() != null) {
                        contratoRepository.findById(dadosAtualizados.getContrato().getId())
                                .ifPresent(projeto::setContrato);
                    }

                    Projeto salvo = projetoRepository.save(projeto);
                    response.put("projeto", salvo);
                    response.put("mensagem", "Projeto atualizado com sucesso!");
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<Projeto>> listarTodos(
            @RequestParam(defaultValue = "false") boolean incluirArquivados) {
        return ResponseEntity.ok(projetoRepository.findAll().stream()
                .filter(item -> incluirArquivados || !Boolean.TRUE.equals(item.getArquivado()))
                .toList());
    }

    @PatchMapping("/{id}/arquivar")
    public ResponseEntity<Projeto> arquivar(@PathVariable Long id, @RequestBody ArquivamentoRequest request) {
        return ResponseEntity.ok(arquivamentoService.arquivarProjeto(id, request.getUsuario(), request.getMotivo()));
    }

    @PatchMapping("/{id}/restaurar")
    public ResponseEntity<Projeto> restaurar(@PathVariable Long id) {
        return ResponseEntity.ok(arquivamentoService.restaurarProjeto(id));
    }

    @GetMapping("/{id}/equipe")
    public ResponseEntity<?> listarEquipe(@PathVariable Long id) {
        return ResponseEntity.ok(projetoEquipeService.listar(id));
    }

    @PutMapping("/{id}/equipe")
    public ResponseEntity<?> substituirEquipe(
            @PathVariable Long id, @RequestBody EquipeProjetoRequest request) {
        return ResponseEntity.ok(projetoEquipeService.substituirEquipe(id, request));
    }

    @ExceptionHandler({ IllegalArgumentException.class, IllegalStateException.class })
    public ResponseEntity<Map<String, String>> handleArquivamentoInvalido(RuntimeException ex) {
        return ResponseEntity.badRequest().body(Map.of("erro", ex.getMessage()));
    }

    /**
     * ✏️ PUT: Atualizar a quantidade utilizada de um material específico na
     * auditoria 💥
     */
    @PutMapping("/{projetoId}/materiais/{materialId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> atualizarQuantidadeUtilizada(
            @PathVariable Long projetoId,
            @PathVariable Long materialId,
            @RequestParam Integer quantidadeUtilizada) {

        Map<String, Object> response = new HashMap<>();
        return projetoRepository.findById(projetoId).map(projeto -> {
            projeto.getMateriais().stream()
                    .filter(m -> m.getId().equals(materialId))
                    .findFirst()
                    .ifPresent(m -> m.setQuantidadeUtilizada(quantidadeUtilizada));

            projetoRepository.save(projeto);
            response.put("mensagem", "Inventário de auditoria sincronizado!");
            return ResponseEntity.ok(response);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/auditoria")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> buscarAuditoria(@PathVariable Long id) {
        Optional<Projeto> projetoOpt = projetoRepository.findById(id);
        if (!projetoOpt.isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Projeto projeto = projetoOpt.get();
        Map<String, Object> response = new HashMap<>();
        response.put("asBuiltStatus", projeto.getAsBuiltStatus() != null ? projeto.getAsBuiltStatus() : "PENDENTE");

        List<Map<String, Object>> materiaisMapeados = projeto.getMateriais().stream().map(mat -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", mat.getId()); // ID do vínculo relacional 💥
            m.put("nome", mat.getMaterial() != null ? mat.getMaterial().getNome() : "Material não identificado");
            m.put("previsto", mat.getQuantidadePrevista());
            m.put("utilizado", mat.getQuantidadeUtilizada());
            return m;
        }).collect(Collectors.toList());

        response.put("materiais", materiaisMapeados); // Chave corrigida para bater com o React! 💥[cite: 1]
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}/as-built/homologar")
    @Transactional
    public ResponseEntity<Map<String, String>> homologarAsBuilt(@PathVariable Long id,
            Authentication authentication) {
        Optional<Projeto> projetoOpt = projetoRepository.findById(id);
        if (!projetoOpt.isPresent()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        Projeto projeto = projetoOpt.get();
        var comarca = comarcaRepository.findByProjetoId(id);
        if (comarca.isPresent()) {
            String responsavel = authentication == null ? "Auditoria de Retirada/Devolução" : authentication.getName();
            if (authentication != null && authentication.getPrincipal() instanceof UsuarioAutenticado usuario) {
                responsavel = usuario.getNome();
            }
            Map<String, Object> auditoria = comarcaService.homologarAsBuilt(
                    comarca.get().getId(), null, responsavel);
            return ResponseEntity.ok(Map.of(
                    "status", auditoria.get("asBuiltStatus").toString(),
                    "mensagem", "As-Built homologado na auditoria da comarca."));
        }
        projeto.setAsBuiltStatus("HOMOLOGADO");
        projetoRepository.save(projeto);

        return ResponseEntity.ok(Map.of(
                "status", "HOMOLOGADO",
                "mensagem", "As-Built homologado com sucesso no banco de dados!"));
    }
}
