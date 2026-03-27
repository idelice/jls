package org.javacs;

import com.sun.source.doctree.*;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.CharBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.javacs.lsp.MarkupContent;
import org.javacs.lsp.MarkupKind;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class MarkdownHelper {

    public static MarkupContent asMarkupContent(DocCommentTree comment) {
        var markdown = asMarkdown(comment);
        var content = new MarkupContent();
        content.kind = MarkupKind.Markdown;
        content.value = markdown;
        return content;
    }

    public static String asMarkdown(DocCommentTree comment) {
        var parts = new ArrayList<String>();
        var firstSentence = renderTrees(comment.getFirstSentence());
        if (!firstSentence.isBlank()) {
            parts.add(firstSentence);
        }
        var body = renderTrees(comment.getBody());
        if (!body.isBlank()) {
            parts.add(body);
        }
        var blockTags = renderBlockTags(comment.getBlockTags());
        if (!blockTags.isBlank()) {
            parts.add(blockTags);
        }
        return String.join("\n\n", parts);
    }

    private static String renderTrees(List<? extends DocTree> trees) {
        var out = new StringBuilder();
        var openTags = new ArrayDeque<String>();
        for (var tree : trees) {
            renderTree(tree, out, openTags);
        }
        return normalizeMarkdown(out.toString());
    }

    private static String renderBlockTags(List<? extends DocTree> tags) {
        var lines = new ArrayList<String>();
        for (var tag : tags) {
            switch (tag.getKind()) {
                case AUTHOR: {
                    var t = (AuthorTree) tag;
                    lines.add(formatBlock("@author", renderTrees(t.getName())));
                    break;
                }
                case SINCE: {
                    var t = (SinceTree) tag;
                    lines.add(formatBlock("@since", renderTrees(t.getBody())));
                    break;
                }
                case SEE: {
                    var t = (SeeTree) tag;
                    var label = renderTrees(t.getReference());
                    lines.add(formatBlock("@see", label));
                    break;
                }
                case PARAM: {
                    var t = (ParamTree) tag;
                    var name = t.getName().toString();
                    var desc = renderTrees(t.getDescription());
                    lines.add(formatParam(name, desc, t.isTypeParameter()));
                    break;
                }
                case RETURN: {
                    var t = (ReturnTree) tag;
                    lines.add(formatBlock("@return", renderTrees(t.getDescription())));
                    break;
                }
                case THROWS:
                case EXCEPTION: {
                    var t = (ThrowsTree) tag;
                    var exception = t.getExceptionName().toString();
                    var desc = renderTrees(t.getDescription());
                    lines.add(formatBlock("@throws", exception + (desc.isBlank() ? "" : " - " + desc)));
                    break;
                }
                case DEPRECATED: {
                    var t = (DeprecatedTree) tag;
                    lines.add(formatBlock("@deprecated", renderTrees(t.getBody())));
                    break;
                }
                default:
                    // Fallback to raw text when we don't recognize a block tag.
                    lines.add(tag.toString());
            }
        }
        return normalizeMarkdown(String.join("\n", lines));
    }

    private static String formatBlock(String label, String body) {
        if (body.isBlank()) return label;
        return label + " " + body;
    }

    private static String formatParam(String name, String desc, boolean typeParam) {
        var prefix = typeParam ? "@typeparam" : "@param";
        if (desc.isBlank()) return prefix + " " + name;
        return prefix + " " + name + " - " + desc;
    }

    private static String normalizeMarkdown(String text) {
        var trimmed = text.trim();
        trimmed = trimmed.replaceAll("[ \\t]+\\n", "\n");
        trimmed = trimmed.replaceAll("\\n{3,}", "\n\n");
        return replaceTags(trimmed);
    }

    private static Document parse(String html) {
        try {
            var xml = "<wrapper>" + html + "</wrapper>";
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            var builder = factory.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void replaceNodes(Document doc, String tagName, Function<String, String> replace) {
        var nodes = doc.getElementsByTagName(tagName);
        while (nodes.getLength() > 0) {
            var node = nodes.item(0);
            var parent = node.getParentNode();
            var text = replace.apply(node.getTextContent().trim());
            var replacement = doc.createTextNode(text);
            parent.replaceChild(replacement, node);
            nodes = doc.getElementsByTagName(tagName);
        }
    }

    private static String print(Document doc) {
        try {
            var tf = TransformerFactory.newInstance();
            var transformer = tf.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            var writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            var wrapped = writer.getBuffer().toString();
            return wrapped.substring("<wrapper>".length(), wrapped.length() - "</wrapper>".length());
        } catch (TransformerException e) {
            throw new RuntimeException(e);
        }
    }

    private static void check(CharBuffer in, char expected) {
        var head = in.get();
        if (head != expected) {
            throw new RuntimeException(String.format("want `%s` got `%s`", expected, head));
        }
    }

    private static boolean empty(CharBuffer in) {
        return in.position() == in.limit();
    }

    private static char peek(CharBuffer in) {
        return in.get(in.position());
    }

    private static String parseTag(CharBuffer in) {
        check(in, '@');
        var tag = new StringBuilder();
        while (!empty(in) && Character.isAlphabetic(peek(in))) {
            tag.append(in.get());
        }
        return tag.toString();
    }

    private static void parseBlock(CharBuffer in, StringBuilder out) {
        check(in, '{');
        if (peek(in) == '@') {
            var tag = parseTag(in);
            if (peek(in) == ' ') in.get();
            switch (tag) {
                case "code":
                case "link":
                case "linkplain":
                    out.append("`");
                    parseInner(in, out);
                    out.append("`");
                    break;
                case "literal":
                    parseInner(in, out);
                    break;
                default:
                    LOG.warning(String.format("Unknown tag `@%s`", tag));
                    parseInner(in, out);
            }
        } else {
            parseInner(in, out);
        }
        check(in, '}');
    }

    private static void parseInner(CharBuffer in, StringBuilder out) {
        while (!empty(in)) {
            switch (peek(in)) {
                case '{':
                    parseBlock(in, out);
                    break;
                case '}':
                    return;
                default:
                    out.append(in.get());
            }
        }
    }

    private static void parse(CharBuffer in, StringBuilder out) {
        while (!empty(in)) {
            parseInner(in, out);
        }
    }

    private static String replaceTags(String in) {
        var out = new StringBuilder();
        parse(CharBuffer.wrap(in), out);
        return out.toString();
    }

    private static String htmlToMarkdown(String html) {
        html = replaceTags(html);

        var doc = parse(html);

        replaceNodes(doc, "i", contents -> String.format("*%s*", contents));
        replaceNodes(doc, "b", contents -> String.format("**%s**", contents));
        replaceNodes(doc, "pre", contents -> String.format("`%s`", contents));
        replaceNodes(doc, "code", contents -> String.format("`%s`", contents));
        replaceNodes(doc, "a", contents -> contents);

        return print(doc);
    }

    private static final Pattern HTML_TAG = Pattern.compile("<(\\w+)[^>]*>");

    private static boolean isHtml(String text) {
        var tags = HTML_TAG.matcher(text);
        while (tags.find()) {
            var tag = tags.group(1);
            var close = String.format("</%s>", tag);
            var findClose = text.indexOf(close, tags.end());
            if (findClose != -1) return true;
        }
        return false;
    }

    /** If `commentText` looks like HTML, convert it to markdown */
    static String asMarkdown(String commentText) {
        if (isHtml(commentText)) {
            try {
                commentText = htmlToMarkdown(commentText);
            } catch (RuntimeException e) {
                LOG.warning("Failed to parse Javadoc HTML, falling back to plain text: " + e.getMessage());
            }
        }
        commentText = replaceTags(commentText);
        return commentText;
    }

    private static void renderTree(DocTree tree, StringBuilder out, ArrayDeque<String> openTags) {
        if (tree instanceof TextTree) {
            var text = ((TextTree) tree).getBody();
            if (!inCodeOrPre(openTags)) {
                text = text.replaceAll("\\s*\\n\\s*", " ");
                text = text.replaceAll(" {2,}", " ");
            }
            out.append(text);
            return;
        }
        if (tree instanceof LiteralTree) {
            out.append("`").append(((LiteralTree) tree).getBody().getBody()).append("`");
            return;
        }
        if (tree instanceof LinkTree) {
            var link = (LinkTree) tree;
            var label = renderTrees(link.getLabel());
            if (!label.isBlank()) {
                out.append(label);
                return;
            }
            var ref = link.getReference() != null ? link.getReference().toString() : "";
            if (!ref.isBlank()) {
                out.append("`").append(ref).append("`");
            }
            return;
        }
        if (tree instanceof SeeTree) {
            out.append(renderTrees(((SeeTree) tree).getReference()));
            return;
        }
        if (tree instanceof StartElementTree) {
            var name = ((StartElementTree) tree).getName().toString().toLowerCase();
            switch (name) {
                case "p":
                    out.append("\n\n");
                    break;
                case "br":
                    out.append("\n");
                    break;
                case "pre":
                    out.append("\n\n```\n");
                    break;
                case "code":
                    out.append("`");
                    break;
                case "b":
                case "strong":
                    out.append("**");
                    break;
                case "i":
                case "em":
                    out.append("*");
                    break;
                default:
                    break;
            }
            openTags.addLast(name);
            return;
        }
        if (tree instanceof EndElementTree) {
            var name = ((EndElementTree) tree).getName().toString().toLowerCase();
            switch (name) {
                case "pre":
                    out.append("\n```\n");
                    break;
                case "code":
                    out.append("`");
                    break;
                case "b":
                case "strong":
                    out.append("**");
                    break;
                case "i":
                case "em":
                    out.append("*");
                    break;
                case "p":
                    out.append("\n\n");
                    break;
                default:
                    break;
            }
            openTags.removeFirstOccurrence(name);
            return;
        }
        if (tree instanceof EntityTree) {
            out.append(decodeEntity(((EntityTree) tree).getName().toString()));
            return;
        }
        if (tree instanceof ErroneousTree) {
            out.append(((ErroneousTree) tree).getBody());
            return;
        }
        if (tree instanceof UnknownInlineTagTree || tree instanceof UnknownBlockTagTree) {
            out.append(tree.toString());
            return;
        }
        out.append(tree.toString());
    }

    private static boolean inCodeOrPre(ArrayDeque<String> openTags) {
        for (var tag : openTags) {
            if ("code".equals(tag) || "pre".equals(tag)) {
                return true;
            }
        }
        return false;
    }

    private static String decodeEntity(String name) {
        return switch (name) {
            case "lt" -> "<";
            case "gt" -> ">";
            case "amp" -> "&";
            case "nbsp" -> " ";
            case "quot" -> "\"";
            default -> "&" + name + ";";
        };
    }

    private static final Logger LOG = Logger.getLogger("main");
}
