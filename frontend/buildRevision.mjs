export function suppliedRevision(value) {
  const revision = value?.trim() || "";
  if (revision && !/^[a-f0-9]{40}$/i.test(revision)) {
    throw new Error("VITE_APP_REVISION deve conter o SHA completo da revisão selecionada.");
  }
  return revision;
}
