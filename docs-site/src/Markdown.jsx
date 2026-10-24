import React, { useMemo, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Highlight } from "prism-react-renderer";
import { slugify } from "./content.js";

// Couleurs du code : des variables CSS, donc le theme clair/sombre change tout seul.
const codeTheme = {
  plain: { color: "var(--code-fg)", backgroundColor: "transparent" },
  styles: [
    { types: ["comment", "prolog", "doctype", "cdata"], style: { color: "var(--hl-com)", fontStyle: "italic" } },
    { types: ["keyword", "tag", "selector", "important", "atrule"], style: { color: "var(--hl-kw)" } },
    { types: ["function", "function-variable"], style: { color: "var(--hl-fn)" } },
    { types: ["string", "char", "attr-value", "regex", "inserted"], style: { color: "var(--hl-str)" } },
    { types: ["number", "boolean", "constant"], style: { color: "var(--hl-num)" } },
    { types: ["class-name", "builtin", "attr-name", "property", "annotation"], style: { color: "var(--hl-type)" } },
  ],
};

// Langages connus de prism-react-renderer ; les autres sont affiches sans couleurs.
const LANG = { kotlin: "kotlin", kt: "kotlin", kts: "kotlin", javascript: "javascript", js: "javascript", json: "json",
  xml: "markup", html: "markup", yaml: "yaml", yml: "yaml", python: "python", go: "go", swift: "swift", cpp: "cpp" };

function CodeBlock({ lang, code }) {
  const [copied, setCopied] = useState(false);
  const numbered = lang && lang !== "text";
  const copy = () => navigator.clipboard?.writeText(code).then(() => {
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  });
  return (
    <div className="code">
      <div className="code-head">
        <span>{lang || "texte"}</span>
        <button onClick={copy} className={copied ? "done" : ""}>{copied ? "Copié" : "Copier"}</button>
      </div>
      <Highlight theme={codeTheme} code={code} language={LANG[lang] || "plain"}>
        {({ tokens, getLineProps, getTokenProps }) => (
          <pre className={numbered ? "numbered" : ""}>
            {tokens.map((line, i) => (
              <div key={i} {...getLineProps({ line })}>
                {numbered && <span className="ln">{i + 1}</span>}
                <span className="lc">
                  {line.map((token, k) => <span key={k} {...getTokenProps({ token })} />)}
                  {line.length === 1 && line[0].empty ? "​" : null}
                </span>
              </div>
            ))}
          </pre>
        )}
      </Highlight>
    </div>
  );
}

const text = (children) => React.Children.toArray(children).map((c) => (typeof c === "string" ? c : text(c.props?.children))).join("");

function heading(Tag, slug) {
  return ({ children }) => {
    const id = slugify(text(children));
    return (
      <Tag id={id}>
        {children}
        {Tag !== "h1" && <a className="anchor" href={`#/${slug}/${id}`} aria-label="Lien">#</a>}
      </Tag>
    );
  };
}

const makeComponents = (slug) => ({
  h1: heading("h1", slug),
  h2: heading("h2", slug),
  h3: heading("h3", slug),
  // Liens internes "08-api-rest.md#x" -> "#/08-api-rest/x"
  a: ({ href = "", children }) => {
    const m = /^([\w-]+)\.md(?:#(.*))?$/.exec(href);
    if (m) return <a href={`#/${m[1]}${m[2] ? "/" + m[2] : ""}`}>{children}</a>;
    return <a href={href} target={href.startsWith("http") ? "_blank" : undefined} rel="noreferrer">{children}</a>;
  },
  pre: ({ children }) => children,
  code: ({ className, children }) => {
    const lang = /language-(\w+)/.exec(className || "")?.[1];
    const code = String(children);
    if (!lang && !code.includes("\n")) return <code className="inline">{children}</code>;
    return <CodeBlock lang={lang} code={code.replace(/\n$/, "")} />;
  },
  table: ({ children }) => <div className="table-wrap"><table>{children}</table></div>,
});

/**
 * Le markdown peut contenir deux blocs speciaux (syntaxe pandoc "fenced divs") :
 *   ::: steps ... :::                         etapes numerotees (chaque ### = une etape)
 *   :::: cards / ::: card ... ::: / ::::      grille de cartes
 */
function splitBlocks(md) {
  const blocks = [];
  let buf = [], mode = null, cards = [], card = null, inCode = false;
  const flush = () => { if (buf.length) blocks.push({ type: "md", text: buf.join("\n") }); buf = []; };
  for (const line of md.split("\n")) {
    if (/^\s*```/.test(line)) inCode = !inCode;
    const t = line.trim();
    if (!inCode && mode === null && t === "::: steps") { flush(); mode = "steps"; continue; }
    if (!inCode && mode === "steps" && t === ":::") { blocks.push({ type: "steps", text: buf.join("\n") }); buf = []; mode = null; continue; }
    if (!inCode && mode === null && t === ":::: cards") { flush(); mode = "cards"; cards = []; continue; }
    if (mode === "cards") {
      if (t === "::: card") { card = []; continue; }
      if (t === ":::" && card) { cards.push(card.join("\n")); card = null; continue; }
      if (t === "::::") { blocks.push({ type: "cards", items: cards }); mode = null; continue; }
      card?.push(line);
      continue;
    }
    buf.push(line);
  }
  flush();
  return blocks;
}

export default function Markdown({ source, slug }) {
  const components = useMemo(() => makeComponents(slug), [slug]);
  const Md = ({ children }) => <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>{children}</ReactMarkdown>;
  return splitBlocks(source).map((b, i) => {
    if (b.type === "steps") return <div className="steps" key={i}><Md>{b.text}</Md></div>;
    if (b.type === "cards") return <div className="cards" key={i}>{b.items.map((c, k) => <div key={k}><Md>{c}</Md></div>)}</div>;
    return <Md key={i}>{b.text}</Md>;
  });
}
