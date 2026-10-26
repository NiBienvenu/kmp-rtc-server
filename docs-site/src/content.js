// Les pages viennent de ../docs/*.md, lues au moment du build (rien a charger en ligne).
const files = import.meta.glob("../../docs/*.md", { query: "?raw", import: "default", eager: true });

export const VERSION = "v1.0";

// Ordre et groupes du sommaire. "badge" : petite etiquette a cote du titre.
export const NAV = [
  { group: "Démarrer", pages: [
    { slug: "index", label: "Présentation" },
    { slug: "01-architecture", label: "Architecture" },
    { slug: "02-configuration", label: "Configuration" },
  ] },
  { group: "Déploiement", pages: [
    { slug: "03-deploiement-ubuntu", label: "Ubuntu (VPS)" },
    { slug: "04-deploiement-windows-macos", label: "Windows et macOS" },
    { slug: "05-ghcr", label: "GitHub Container Registry" },
  ] },
  { group: "Réseau et sécurité", pages: [
    { slug: "06-reseau-sip-rtp-nat", label: "SIP, RTP, NAT, TURN" },
    { slug: "07-securite", label: "Sécurité" },
  ] },
  { group: "Développer", pages: [
    { slug: "09-integration-kmp", label: "Intégration KMP", badge: "Exemple" },
    { slug: "08-api-rest", label: "API REST" },
    { slug: "11-enregistrements", label: "Enregistrements" },
  ] },
  { group: "Aide", pages: [
    { slug: "10-depannage", label: "Dépannage" },
    { slug: "12-licences-hors-ligne", label: "Licences et hors ligne" },
  ] },
];

export const PAGES = NAV.flatMap((g) => g.pages.map((p) => ({ ...p, group: g.group })));

export function source(slug) {
  const key = Object.keys(files).find((k) => k.endsWith(`/${slug}.md`));
  return key ? files[key] : `# Page introuvable\n\nLa page \`${slug}\` n'existe pas.`;
}

/** Identifiant d'ancre stable : "Réseau & NAT" -> "reseau-nat" */
export function slugify(text) {
  return String(text).toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "")
    .replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "");
}

/** Lignes hors blocs de code (pour ne pas prendre un commentaire "# ..." pour un titre). */
function proseLines(md) {
  let inCode = false;
  return md.split("\n").filter((l) => {
    if (/^\s*```/.test(l)) { inCode = !inCode; return false; }
    return !inCode;
  });
}

export function headings(md) {
  return proseLines(md)
    .map((l) => /^(#{1,3})\s+(.*)$/.exec(l))
    .filter(Boolean)
    .map(([, h, text]) => ({ level: h.length, text: text.trim(), id: slugify(text) }));
}

/** Index de recherche : un element par page et par titre de section. */
export const SEARCH_INDEX = PAGES.flatMap((p) => {
  const md = source(p.slug);
  const out = [{ title: p.label, page: p.label, slug: p.slug, id: "", text: md }];
  const lines = md.split("\n");
  let cur = null;
  for (const l of lines) {
    const m = /^(##|###)\s+(.*)$/.exec(l);
    if (m) { cur = { title: m[2].trim(), page: p.label, slug: p.slug, id: slugify(m[2]), text: "" }; out.push(cur); }
    else if (cur) cur.text += " " + l;
  }
  return out;
});
