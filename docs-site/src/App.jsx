import React, { useEffect, useMemo, useRef, useState } from "react";
import Markdown from "./Markdown.jsx";
import { NAV, PAGES, SEARCH_INDEX, VERSION, headings, source } from "./content.js";

// ─── Routage par hash : #/page ou #/page/section (marche sous /docs/ sans regle Nginx) ──
function readRoute() {
  const [, slug = "index", section = ""] = location.hash.split("/");
  return { slug: slug || "index", section: decodeURIComponent(section) };
}

function useRoute() {
  const [route, setRoute] = useState(readRoute);
  useEffect(() => {
    const on = () => setRoute(readRoute());
    addEventListener("hashchange", on);
    return () => removeEventListener("hashchange", on);
  }, []);
  return route;
}

function useTheme() {
  const prefersDark = matchMedia("(prefers-color-scheme: dark)").matches;
  const [theme, setTheme] = useState(() => document.documentElement.dataset.theme || (prefersDark ? "dark" : "light"));
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem("docs-theme", theme); } catch (e) { /* navigation privee */ }
  }, [theme]);
  return [theme, () => setTheme(theme === "dark" ? "light" : "dark")];
}

// ─── Icones (traits, 24x24) ────────────────────────────────────────────────
const I = {
  search: <><circle cx="11" cy="11" r="7" /><path d="m20 20-3.5-3.5" /></>,
  sun: <><circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></>,
  moon: <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />,
  menu: <path d="M4 6h16M4 12h16M4 18h16" />,
  home: <path d="M3 11 12 4l9 7M5 10v10h14V10" />,
  chevron: <path d="m6 9 6 6 6-6" />,
  up: <path d="M7 10v11H3V10zM7 10l4-8a3 3 0 0 1 3 3v4h6a2 2 0 0 1 2 2.3l-1.4 8A2 2 0 0 1 18.6 21H7" />,
  down: <path d="M17 14V3h4v11zM17 14l-4 8a3 3 0 0 1-3-3v-4H4a2 2 0 0 1-2-2.3l1.4-8A2 2 0 0 1 5.4 3H17" />,
  github: <path d="M9 19c-4.3 1.4-4.3-2.5-6-3m12 5v-3.5c0-1 .1-1.4-.5-2 2.8-.3 5.5-1.4 5.5-6a4.6 4.6 0 0 0-1.3-3.2 4.2 4.2 0 0 0-.1-3.2s-1.1-.3-3.5 1.3a12.3 12.3 0 0 0-6.2 0C6.5 2.8 5.4 3.1 5.4 3.1a4.2 4.2 0 0 0-.1 3.2A4.6 4.6 0 0 0 4 9.5c0 4.6 2.7 5.7 5.5 6-.6.6-.6 1.2-.5 2V21" />,
};
const Icon = ({ name, size = 20 }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{I[name]}</svg>
);

// ─── Recherche ─────────────────────────────────────────────────────────────
const norm = (s) => s.toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "");

function Search() {
  const [q, setQ] = useState("");
  const [open, setOpen] = useState(false);
  const [sel, setSel] = useState(0);
  const input = useRef(null);

  useEffect(() => {
    const onKey = (e) => {
      if ((e.key === "/" || (e.key === "k" && (e.ctrlKey || e.metaKey))) && document.activeElement !== input.current) {
        e.preventDefault();
        input.current.focus();
      }
    };
    addEventListener("keydown", onKey);
    return () => removeEventListener("keydown", onKey);
  }, []);

  const hits = useMemo(() => {
    const words = norm(q).split(/\s+/).filter(Boolean);
    if (!words.length) return [];
    return SEARCH_INDEX.map((e) => {
      const t = norm(e.title), x = norm(e.text);
      let score = 0;
      for (const w of words) {
        if (t.includes(w)) score += 10;
        else if (x.includes(w)) score += 1;
        else return null;
      }
      return { ...e, score };
    }).filter(Boolean).sort((a, b) => b.score - a.score).slice(0, 10);
  }, [q]);

  const go = (h) => {
    location.hash = `/${h.slug}${h.id ? "/" + h.id : ""}`;
    setOpen(false);
    setQ("");
    input.current.blur();
  };

  return (
    <div className="search">
      <Icon name="search" size={18} />
      <input
        ref={input}
        type="search"
        value={q}
        placeholder="Rechercher dans la doc..."
        onChange={(e) => { setQ(e.target.value); setSel(0); setOpen(true); }}
        onFocus={() => setOpen(true)}
        onBlur={() => setTimeout(() => setOpen(false), 150)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown") { e.preventDefault(); setSel(Math.min(sel + 1, hits.length - 1)); }
          if (e.key === "ArrowUp") { e.preventDefault(); setSel(Math.max(sel - 1, 0)); }
          if (e.key === "Enter" && hits[sel]) go(hits[sel]);
          if (e.key === "Escape") input.current.blur();
        }}
      />
      <kbd>/</kbd>
      {open && q.trim() && (
        <div className="results">
          {hits.length === 0 && <div className="empty">Aucun résultat pour « {q} »</div>}
          {hits.map((h, i) => (
            <button key={h.slug + h.id} className={i === sel ? "sel" : ""} onMouseDown={() => go(h)}>
              {h.title}
              <small>{h.page}</small>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

// ─── Mise en page ──────────────────────────────────────────────────────────
function Header({ slug, theme, toggleTheme, onMenu }) {
  const links = [["index", "Démarrer"], ["08-api-rest", "API"], ["09-integration-kmp", "Intégration KMP"]];
  return (
    <header className="topbar">
      <button className="icon-btn menu-btn" onClick={onMenu} aria-label="Menu"><Icon name="menu" /></button>
      <a href="#/index" className="brand">
        <span className="logo"><svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M15 10.5V7a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1v-3.5l5 4v-11z" /></svg></span>
        <span className="brand-name">kmp-rtc</span>
      </a>
      <span className="version">{VERSION}</span>
      <Search />
      <nav className="top-links">
        {links.map(([s, label]) => <a key={s} href={`#/${s}`} className={slug === s ? "on" : ""}>{label}</a>)}
        <a href="/admin/">Admin</a>
      </nav>
      <button className="icon-btn" onClick={toggleTheme} aria-label="Changer de thème"><Icon name={theme === "dark" ? "moon" : "sun"} /></button>
    </header>
  );
}

function Sidebar({ slug, onPick }) {
  const [closed, setClosed] = useState({});
  return (
    <aside className="sidebar">
      <p className="sidebar-title">Sommaire</p>
      {NAV.map((g) => {
        const isClosed = closed[g.group] && !g.pages.some((p) => p.slug === slug);
        return (
          <div className="group" key={g.group}>
            <button className={`group-head ${isClosed ? "closed" : ""}`} onClick={() => setClosed({ ...closed, [g.group]: !closed[g.group] })}>
              {g.group}<Icon name="chevron" size={18} />
            </button>
            {!isClosed && g.pages.map((p) => (
              <a key={p.slug} href={`#/${p.slug}`} onClick={onPick} className={p.slug === slug ? "on" : ""}>
                {p.label}
                {p.badge && <span className="tag">{p.badge}</span>}
              </a>
            ))}
          </div>
        );
      })}
    </aside>
  );
}

function OnThisPage({ slug, items, active }) {
  const [vote, setVote] = useState(null);
  useEffect(() => setVote(null), [slug]);
  return (
    <aside className="onpage">
      <p className="onpage-title">Sur cette page</p>
      <nav className="toc">
        <a href={`#/${slug}`} className={!active ? "on" : ""} onClick={() => scrollTo({ top: 0 })}>Vue d'ensemble</a>
        {items.map((h) => (
          <a key={h.id} href={`#/${slug}/${h.id}`} className={`l${h.level} ${active === h.id ? "on" : ""}`}>{h.text}</a>
        ))}
      </nav>
      <div className="feedback">
        <p className="feedback-title">Cette page vous aide ?</p>
        {vote ? <p className="feedback-text">Merci pour votre retour !</p> : <>
          <p className="feedback-text">Dites-le nous pour améliorer la documentation.</p>
          <div className="feedback-btns">
            <button className="icon-btn" onClick={() => setVote("up")} aria-label="Oui"><Icon name="up" size={18} /></button>
            <button className="icon-btn" onClick={() => setVote("down")} aria-label="Non"><Icon name="down" size={18} /></button>
          </div>
        </>}
      </div>
    </aside>
  );
}

export default function App() {
  const { slug, section } = useRoute();
  const [theme, toggleTheme] = useTheme();
  const [menu, setMenu] = useState(false);
  const [active, setActive] = useState("");
  const md = source(slug);
  const items = useMemo(() => headings(md).filter((h) => h.level > 1), [md]);
  const index = PAGES.findIndex((p) => p.slug === slug);
  const page = PAGES[index];
  const prev = PAGES[index - 1], next = PAGES[index + 1];

  useEffect(() => { document.title = `${page?.label ?? "Docs"} | kmp-rtc-server`; }, [page]);

  // Aller a la section demandee, ou en haut quand on change de page
  useEffect(() => {
    const el = section && document.getElementById(section);
    if (el) el.scrollIntoView();
    else scrollTo({ top: 0 });
  }, [slug, section]);

  // Section visible -> surlignee dans "Sur cette page"
  useEffect(() => {
    const onScroll = () => {
      let cur = "";
      for (const h of items) {
        const el = document.getElementById(h.id);
        if (el && el.getBoundingClientRect().top < 140) cur = h.id;
      }
      setActive(cur);
    };
    onScroll();
    addEventListener("scroll", onScroll, { passive: true });
    return () => removeEventListener("scroll", onScroll);
  }, [items]);

  return (
    <div className={menu ? "nav-open" : ""}>
      <Header slug={slug} theme={theme} toggleTheme={toggleTheme} onMenu={() => setMenu(!menu)} />
      <div className="shell">
        <Sidebar slug={slug} onPick={() => setMenu(false)} />
        <main className="content">
          <nav className="crumbs" aria-label="Fil d'Ariane">
            <a href="#/index" aria-label="Accueil"><Icon name="home" size={17} /></a>
            <span className="sep">›</span><a href="#/index">Accueil</a>
            {page && <><span className="sep">›</span><span>{page.group}</span></>}
            <span className="sep">›</span><span className="here">{page?.label ?? slug}</span>
          </nav>
          <article key={slug}><Markdown source={md} slug={slug} /></article>
          <nav className="pager">
            {prev && <a className="prev" href={`#/${prev.slug}`}><small>‹ Précédent</small>{prev.label}</a>}
            {next && <a className="next" href={`#/${next.slug}`}><small>Suivant ›</small>{next.label}</a>}
          </nav>
        </main>
        <OnThisPage slug={slug} items={items} active={active} />
      </div>
      <div className="scrim" onClick={() => setMenu(false)} />
    </div>
  );
}
