export const MARCA_SIMULACAO = "TESTE — SEM VALIDADE OPERACIONAL";
export const EMPRESA_FICTICIA = "EMPRESA FICTÍCIA TESTE — HOMOLOGAÇÃO";
export const CAMPOS_IDENTIDADE_FICTICIA = [
  "equipeResponsavel", "gestorRc", "gerenteForum", "tecnicoResponsavel", "gestorProjetoRc",
  "responsavelDesignadoNome", "gerenteDesignanteNome", "recebidoPor", "cargoGerente",
  "responsavelDesignadoCargo", "carimboGerente", "contrato", "projeto", "comarcaForum", "endereco",
];
export const camposFicticios = () => ({
  ...Object.fromEntries(CAMPOS_IDENTIDADE_FICTICIA.map(campo => [campo, "TESTE FICTÍCIO"])),
  cpfTecnico: "NÃO APLICÁVEL — IDENTIDADE FICTÍCIA",
});
