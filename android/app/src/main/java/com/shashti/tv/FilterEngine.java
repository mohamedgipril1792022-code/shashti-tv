package com.shashti.tv;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Adblock filter engine for the Adblock Plus / uBlock Origin list syntax (EasyList, EasyPrivacy, uBO filters, Liste AR).
 * Network rules: patterns with || | ^ * anchors, options (resource types, third-party, domain=, popup) and @@ exceptions.
 * Cosmetic rules: ## (generic and per-site) with #@# exceptions, and $elemhide / $generichide page exceptions.
 * Matching uses a token index like uBlock Origin, so each request only checks a handful of rules.
 * Plain Java (no Android classes) so it can be tested on any JVM.
 */
public class FilterEngine {
    public static final int SCRIPT = 1, IMAGE = 2, STYLESHEET = 4, SUBDOCUMENT = 8, XHR = 16, MEDIA = 32, FONT = 64,
            OTHER = 128, POPUP = 256, DOCUMENT = 512;
    private static final int ALL_NORMAL = SCRIPT | IMAGE | STYLESHEET | SUBDOCUMENT | XHR | MEDIA | FONT | OTHER;

    static final class Rule {
        String pattern;          // the original pattern (without options)
        Pattern regex;           // compiled lazily
        int types = ALL_NORMAL;
        int party = 0;           // 0 any, 1 third-party only, 2 first-party only
        String[] incDomains, excDomains;
        boolean important;
        String hostAnchor;       // fast path: pattern is exactly ||host^
    }

    private final Map<String, List<Rule>> blockIdx = new HashMap<>();
    private final Map<String, List<Rule>> allowIdx = new HashMap<>();
    private final List<Rule> blockAny = new ArrayList<>();
    private final List<Rule> allowAny = new ArrayList<>();
    private final Set<String> blockHosts = new HashSet<>();      // ||host^ with no options: fastest path
    // cosmetic
    private final Map<String, List<String>> siteHide = new HashMap<>();
    private final Map<String, Set<String>> siteUnhide = new HashMap<>();
    private final List<String> genericHide = new ArrayList<>();
    private final Set<String> genericUnhide = new HashSet<>();
    private final Set<String> noElemhide = new HashSet<>(), noGenerichide = new HashSet<>();
    public int ruleCount = 0;

    // ---------------------------------------------------------------- loading

    public synchronized void load(File f, boolean genericCosmetics) {
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) add(line.trim(), genericCosmetics);
        } catch (Exception ignored) { }
    }

    public synchronized void add(String line, boolean genericCosmetics) {
        if (line.isEmpty() || line.charAt(0) == '!' || line.charAt(0) == '[') return;
        int sep = cosmeticSep(line);
        if (sep >= 0) { addCosmetic(line, sep, genericCosmetics); return; }
        if (line.contains("#$#") || line.contains("#?#") || line.contains("#%#")) return; // snippets/extended: not supported
        addNetwork(line);
    }

    private static int cosmeticSep(String l) {
        int i = l.indexOf("##");
        int j = l.indexOf("#@#");
        if (j >= 0 && (i < 0 || j < i)) return j;
        return i;
    }

    private void addCosmetic(String line, int sep, boolean generic) {
        boolean exception = line.startsWith("#@#", sep);
        String sel = line.substring(sep + (exception ? 3 : 2)).trim();
        if (sel.isEmpty() || sel.startsWith("+js") || sel.startsWith("^") || sel.contains(":-abp-") || sel.contains(":has-text")
                || sel.contains(":xpath") || sel.contains(":style(") || sel.contains(":remove") || sel.contains(":matches-")
                || sel.contains(":upward") || sel.contains(":watch-attr") || sel.contains(":min-text-length") || sel.contains("{")) return;
        String doms = line.substring(0, sep).trim();
        if (doms.isEmpty()) {
            if (exception) genericUnhide.add(sel);
            else if (generic) genericHide.add(sel);
            return;
        }
        for (String d : doms.split(",")) {
            d = d.trim().toLowerCase(Locale.ROOT);
            if (d.isEmpty() || d.contains("*") || d.contains("/")) continue;
            if (d.startsWith("~")) continue; // "everywhere except" rules: skipped (rare, and wrong-site hiding is worse)
            if (exception) siteUnhide.computeIfAbsent(d, k -> new HashSet<>()).add(sel);
            else siteHide.computeIfAbsent(d, k -> new ArrayList<>()).add(sel);
        }
        ruleCount++;
    }

    private void addNetwork(String line) {
        boolean exception = line.startsWith("@@");
        if (exception) line = line.substring(2);
        if (line.length() > 1 && line.startsWith("/") && line.endsWith("/")) return; // regex rules: skipped
        String opts = null;
        int d = line.lastIndexOf('$');
        if (d >= 0 && d < line.length() - 1 && line.indexOf('/', d) < 0) { opts = line.substring(d + 1); line = line.substring(0, d); }
        Rule r = new Rule();
        r.pattern = line.toLowerCase(Locale.ROOT);
        if (opts != null && !parseOptions(r, opts, exception)) return;
        if (r.pattern.isEmpty() || r.pattern.equals("*") || r.pattern.equals("||")) {
            // page-wide rules such as @@||site^$elemhide are handled in parseOptions; a bare "*" rule needs domain= to make sense
            if (r.incDomains == null) return;
            r.pattern = "*";
        }
        ruleCount++;
        // fast path ||host^ with no options
        if (!exception && opts == null && r.pattern.startsWith("||") && r.pattern.endsWith("^")) {
            String h = r.pattern.substring(2, r.pattern.length() - 1);
            if (h.matches("[a-z0-9.\\-]+")) { blockHosts.add(h); return; }
        }
        if (r.pattern.startsWith("||") && r.pattern.endsWith("^")) {
            String h = r.pattern.substring(2, r.pattern.length() - 1);
            if (h.matches("[a-z0-9.\\-]+")) r.hostAnchor = h;
        }
        String tok = bestToken(r.pattern);
        Map<String, List<Rule>> idx = exception ? allowIdx : blockIdx;
        if (tok == null) (exception ? allowAny : blockAny).add(r);
        else idx.computeIfAbsent(tok, k -> new ArrayList<>(2)).add(r);
    }

    /** @return false when the rule should be ignored (unsupported option). */
    private boolean parseOptions(Rule r, String opts, boolean exception) {
        int inc = 0, exc = 0;
        for (String o : opts.toLowerCase(Locale.ROOT).split(",")) {
            o = o.trim();
            boolean neg = o.startsWith("~");
            String k = neg ? o.substring(1) : o;
            int t = typeOf(k);
            if (t != 0) { if (neg) exc |= t; else inc |= t; continue; }
            switch (k) {
                case "third-party": case "3p": r.party = neg ? 2 : 1; break;
                case "first-party": case "1p": r.party = neg ? 1 : 2; break;
                case "important": r.important = true; break;
                case "match-case": case "all": break;
                case "elemhide": case "ehide": case "generichide": case "ghide":
                    if (exception) {
                        String h = r.pattern.startsWith("||") ? r.pattern.substring(2).replace("^", "").replace("*", "") : null;
                        if (h != null && h.matches("[a-z0-9.\\-]+")) (k.startsWith("g") ? noGenerichide : noElemhide).add(h);
                    }
                    return false;
                default:
                    if (k.startsWith("domain=") || k.startsWith("from=")) {
                        List<String> in = new ArrayList<>(), ex = new ArrayList<>();
                        for (String dd : k.substring(k.indexOf('=') + 1).split("\\|")) {
                            if (dd.isEmpty() || dd.contains("*")) continue;
                            if (dd.startsWith("~")) ex.add(dd.substring(1)); else in.add(dd);
                        }
                        if (!in.isEmpty()) r.incDomains = in.toArray(new String[0]);
                        if (!ex.isEmpty()) r.excDomains = ex.toArray(new String[0]);
                    } else if (k.startsWith("redirect=") || k.startsWith("redirect-rule=") || k.equals("empty") || k.equals("mp4")) {
                        // block (we have no replacement resources)
                    } else {
                        return false; // csp=, removeparam=, header=, permissions=, websocket, ping, etc.
                    }
            }
        }
        if (inc != 0) r.types = inc;
        else if (exc != 0) r.types = ALL_NORMAL & ~exc;
        return true;
    }

    private static int typeOf(String k) {
        switch (k) {
            case "script": return SCRIPT;
            case "image": return IMAGE;
            case "stylesheet": case "css": return STYLESHEET;
            case "subdocument": case "frame": return SUBDOCUMENT;
            case "xmlhttprequest": case "xhr": return XHR;
            case "media": return MEDIA;
            case "font": return FONT;
            case "other": case "object": case "object-subrequest": return OTHER;
            case "popup": return POPUP;
            case "document": case "doc": return DOCUMENT;
            default: return 0;
        }
    }

    /** Longest literal run of [a-z0-9%] (3+ chars) not touching a wildcard — the same idea uBlock uses. */
    static String bestToken(String p) {
        String best = null;
        int n = p.length(), i = 0;
        while (i < n) {
            while (i < n && !tokChar(p.charAt(i))) i++;
            int s = i;
            while (i < n && tokChar(p.charAt(i))) i++;
            if (i - s >= 3) {
                boolean starBefore = s > 0 && p.charAt(s - 1) == '*';
                boolean starAfter = i < n && p.charAt(i) == '*';
                boolean openStart = s == 0 && !p.startsWith("|");     // pattern may continue to the left
                boolean openEnd = i == n && !p.endsWith("|") && !p.endsWith("^");
                if (!starBefore && !starAfter && !openStart && !openEnd) {
                    String t = p.substring(s, i);
                    if (best == null || t.length() > best.length()) best = t;
                }
            }
        }
        return best;
    }

    private static boolean tokChar(char c) { return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '%'; }

    // ---------------------------------------------------------------- matching

    /**
     * @param url      request URL
     * @param pageHost host of the page the request belongs to (top page)
     * @param type     one of the type constants
     * @return true when the request should be blocked
     */
    public boolean shouldBlock(String url, String pageHost, int type) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.ROOT);
        String host = hostOf(u);
        boolean third = pageHost == null || !sameSite(host, pageHost);
        Rule block = null;
        if (type != POPUP && type != DOCUMENT) {
            for (String h = host; h != null; h = parent(h)) if (blockHosts.contains(h)) { block = HOST_RULE; break; }
        }
        Set<String> toks = null;
        if (block == null) {
            toks = tokens(u);
            block = find(blockIdx, blockAny, toks, u, host, pageHost, type, third);
        }
        if (block == null) return false;
        if (block.important) return true;
        if (toks == null) toks = tokens(u);
        return find(allowIdx, allowAny, toks, u, host, pageHost, type, third) == null;
    }

    private static final Rule HOST_RULE = new Rule();

    private Rule find(Map<String, List<Rule>> idx, List<Rule> any, Set<String> toks, String u, String host, String pageHost, int type, boolean third) {
        for (String t : toks) {
            List<Rule> l = idx.get(t);
            if (l == null) continue;
            for (Rule r : l) if (matches(r, u, host, pageHost, type, third)) return r;
        }
        for (Rule r : any) if (matches(r, u, host, pageHost, type, third)) return r;
        return null;
    }

    private boolean matches(Rule r, String u, String host, String pageHost, int type, boolean third) {
        if ((r.types & type) == 0) return false;
        if (r.party == 1 && !third) return false;
        if (r.party == 2 && third) return false;
        if (r.incDomains != null || r.excDomains != null) {
            if (pageHost == null) return false;
            if (r.excDomains != null) for (String d : r.excDomains) if (onDomain(pageHost, d)) return false;
            if (r.incDomains != null) {
                boolean ok = false;
                for (String d : r.incDomains) if (onDomain(pageHost, d)) { ok = true; break; }
                if (!ok) return false;
            }
        }
        if (r.pattern.equals("*")) return true;
        if (r.hostAnchor != null) {
            for (String h = host; h != null; h = parent(h)) if (h.equals(r.hostAnchor)) return true;
            return false;
        }
        Pattern p = r.regex;
        if (p == null) {
            try { p = Pattern.compile(toRegex(r.pattern)); } catch (Exception e) { p = Pattern.compile("(?!)"); }
            r.regex = p;
        }
        return p.matcher(u).find();
    }

    static String toRegex(String p) {
        StringBuilder sb = new StringBuilder();
        int i = 0, n = p.length();
        if (p.startsWith("||")) { sb.append("^[a-z][a-z0-9+.\\-]*://(?:[^/?#]*\\.)?"); i = 2; }
        else if (p.startsWith("|")) { sb.append('^'); i = 1; }
        boolean endAnchor = n > i && p.endsWith("|");
        int end = endAnchor ? n - 1 : n;
        for (; i < end; i++) {
            char c = p.charAt(i);
            if (c == '*') sb.append(".*");
            else if (c == '^') sb.append("(?:[^a-z0-9_\\-.%]|$)");
            else if ("\\.+?()[]{}$|/".indexOf(c) >= 0) sb.append('\\').append(c);
            else sb.append(c);
        }
        if (endAnchor) sb.append('$');
        return sb.toString();
    }

    private static Set<String> tokens(String u) {
        Set<String> s = new HashSet<>();
        int n = u.length(), i = 0;
        while (i < n) {
            while (i < n && !tokChar(u.charAt(i))) i++;
            int st = i;
            while (i < n && tokChar(u.charAt(i))) i++;
            if (i - st >= 3) s.add(u.substring(st, i));
        }
        return s;
    }

    // ---------------------------------------------------------------- cosmetics

    /** CSS selectors to hide on this host (site rules + generic rules), minus exceptions. */
    public synchronized String hideSelectors(String host) {
        if (host == null) return "";
        host = host.toLowerCase(Locale.ROOT);
        for (String h = host; h != null; h = parent(h)) if (noElemhide.contains(h)) return "";
        Set<String> unhide = new HashSet<>(genericUnhide);
        for (String h = host; h != null; h = parent(h)) { Set<String> x = siteUnhide.get(h); if (x != null) unhide.addAll(x); }
        StringBuilder out = new StringBuilder();
        for (String h = host; h != null; h = parent(h)) {
            List<String> l = siteHide.get(h);
            if (l != null) for (String s : l) if (!unhide.contains(s)) append(out, s);
        }
        boolean gen = true;
        for (String h = host; h != null; h = parent(h)) if (noGenerichide.contains(h)) gen = false;
        if (gen) for (String s : genericHide) if (!unhide.contains(s)) append(out, s);
        return out.toString();
    }

    private static void append(StringBuilder b, String s) { if (b.length() > 0) b.append(','); b.append(s); }

    // ---------------------------------------------------------------- helpers

    static String hostOf(String u) {
        int s = u.indexOf("://");
        if (s < 0) return "";
        s += 3;
        int e = s;
        while (e < u.length() && "/?#:".indexOf(u.charAt(e)) < 0) e++;
        String h = u.substring(s, e);
        int at = h.lastIndexOf('@');
        return at >= 0 ? h.substring(at + 1) : h;
    }

    static String parent(String h) {
        int i = h.indexOf('.');
        if (i < 0 || h.indexOf('.', i + 1) < 0) return null; // stop at the registrable part (example.com)
        return h.substring(i + 1);
    }

    static boolean onDomain(String host, String d) { return host.equals(d) || host.endsWith("." + d); }

    static boolean sameSite(String a, String b) {
        if (a == null || b == null) return false;
        return base(a).equals(base(b));
    }

    static String base(String h) {
        String[] p = h.toLowerCase(Locale.ROOT).split("\\.");
        if (p.length <= 2) return h;
        String last2 = p[p.length - 2] + "." + p[p.length - 1];
        // common two-part suffixes (e.g. example.com.eg, example.co.uk)
        if (p.length >= 3 && (p[p.length - 2].equals("com") || p[p.length - 2].equals("co") || p[p.length - 2].equals("net") || p[p.length - 2].equals("org")) && p[p.length - 1].length() == 2)
            return p[p.length - 3] + "." + last2;
        return last2;
    }
}
