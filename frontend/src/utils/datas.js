export function formatarDataCivil(data) {
  if (!data) return "--";

  const valor = String(data);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(valor)) return "--";

  const dataUtc = new Date(`${valor}T00:00:00Z`);
  if (Number.isNaN(dataUtc.getTime()) || dataUtc.toISOString().slice(0, 10) !== valor) {
    return "--";
  }

  return new Intl.DateTimeFormat("pt-BR", { timeZone: "UTC" }).format(dataUtc);
}
