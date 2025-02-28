package dev.ebullient.convert.io;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.QualifiedNameable;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.DocumentationTool;
import javax.tools.ToolProvider;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.LinkTree;
import com.sun.source.doctree.LiteralTree;
import com.sun.source.doctree.TextTree;
import com.sun.source.util.DocTrees;

import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;

public class MarkdownDoclet implements Doclet {
    Reporter reporter;
    DocletEnvironment environment;
    Path outputDirectory;
    Path currentResource;

    MarkdownOption targetDir = new MarkdownOption() {
        String value;

        @Override
        public int getArgumentCount() {
            return 2;
        }

        @Override
        public String getDescription() {
            return "The target output directory.";
        }

        @Override
        public Kind getKind() {
            return Doclet.Option.Kind.STANDARD;
        }

        @Override
        public List<String> getNames() {
            return List.of("-d", "--destination");
        }

        @Override
        public String getParameters() {
            return "directory";
        }

        public String getValue() {
            return (value != null) ? value : "docs/";
        }

        @Override
        public boolean process(String option, List<String> arguments) {
            value = arguments.get(0);
            return true;
        }
    };

    @Override
    public void init(Locale locale, Reporter reporter) {
        this.reporter = reporter;
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public Set<? extends Option> getSupportedOptions() {
        return Set.of(targetDir);
    }

    @Override
    public boolean run(DocletEnvironment environment) {
        try {
            processFiles(environment);
        } catch (final Exception e) {
            reporter.print(Diagnostic.Kind.ERROR, e.getMessage());
            e.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * Process classes and create markdown files.
     *
     * @throws IOException
     */
    protected void processFiles(DocletEnvironment environment) throws IOException {
        this.environment = environment;
        DocTrees docTrees = environment.getDocTrees();

        outputDirectory = Paths.get(targetDir.getValue());
        if (!Files.exists(outputDirectory)) {
            Files.createDirectories(outputDirectory);
        }
        reporter.print(Diagnostic.Kind.NOTE, "Writing to " + outputDirectory.toAbsolutePath());

        Set<? extends Element> elements = environment.getIncludedElements();

        // Print package indexes (README.md)
        for (PackageElement p : ElementFilter.packagesIn(elements)) {
            writeReadmeFile(docTrees, p);
        }

        for (TypeElement t : ElementFilter.typesIn(elements)) {
            if (t.getKind() == ElementKind.INTERFACE) {
                continue;
            }
            writeReferenceFile(docTrees, t);
        }
    }

    protected void writeReferenceFile(DocTrees docTrees, TypeElement t) throws IOException {
        String name = t.getSimpleName().toString();
        if (name.contains("Builder")) {
            return;
        }
        Path outFile = getOutputFile(t);
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(outFile))) {
            Aggregator aggregator = new Aggregator();
            aggregator.add("# " + name + "\n\n");

            // Add class description
            aggregator.addFullBody(docTrees.getDocCommentTree(t));

            // Add class attributes and methods
            Map<String, Element> members = new TreeMap<>();
            for (Element e : t.getEnclosedElements()) {
                processElement(docTrees, members, e);
            }

            // Include attributes and methods from superclass
            TypeElement superType = getSuperclassElement(t);
            while (superType != null) {
                for (Element e : superType.getEnclosedElements()) {
                    processElement(docTrees, members, e);
                }
                superType = getSuperclassElement(superType);
            }

            aggregator.add("\n\n## Attributes\n\n");
            aggregator.add(members.keySet().stream()
                    .map(s -> String.format("[%s](#%s)", s, s.toLowerCase()))
                    .collect(Collectors.joining(", ")));
            aggregator.add("\n\n");

            for (Map.Entry<String, Element> entry : members.entrySet()) {
                aggregator.add("\n\n### " + entry.getKey() + "\n\n");
                aggregator.addFullBody(docTrees.getDocCommentTree(entry.getValue()));
            }
            out.println(aggregator.toString());
        }
    }

    protected void processElement(DocTrees docTrees, Map<String, Element> members, Element e) {
        String name = e.getSimpleName().toString();
        ElementKind kind = e.getKind();
        if (!e.getModifiers().stream().anyMatch(m -> m == Modifier.PUBLIC)
                || e.getModifiers().stream().anyMatch(m -> m == Modifier.STATIC)) {
            return;
        }
        if (kind == ElementKind.METHOD) {
            if (!name.startsWith("get") && !name.startsWith("is")) {
                return;
            }
            if (e.getAnnotation(Deprecated.class) != null) {
                return;
            }
        } else if (!kind.isField()) {
            return;
        }

        if (e.getKind() == ElementKind.METHOD) {
            name = name.replaceFirst("(get|is)", "");
            name = name.substring(0, 1).toLowerCase() + name.substring(1);
        }
        members.put(name, e);
    }

    void writeReadmeFile(DocTrees docTrees, PackageElement p) throws IOException {
        Path outFile = getOutputFile(p);
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(outFile))) {
            Aggregator aggregator = new Aggregator();

            // Add package description
            aggregator.addFullBody(docTrees.getDocCommentTree(p));

            // Make list linking to package members
            Map<String, TypeElement> members = new TreeMap<>();
            for (Element e : p.getEnclosedElements()) {
                if (isExcluded(e)) {
                    continue;
                }
                TypeElement te = (TypeElement) e;
                if (te.getKind() == ElementKind.INTERFACE) {
                    continue;
                }
                members.put(te.getSimpleName().toString(), te);
            }

            Aggregator elements = new Aggregator();
            members.values().forEach(te -> elements.add("- [" + te.getSimpleName() + "](" + te.getSimpleName() + ".md)"
                    + getDescription(docTrees, te) + "\n"));

            String result = elements.toString();
            if (!result.isEmpty()) {
                aggregator.add("\n\n## References\n\n");
                aggregator.add(result);
            }
            out.println(aggregator.toString());
        }
    }

    boolean isExcluded(Element element) {
        ElementKind kind = element.getKind();
        return !environment.isIncluded(element)
                || element.getSimpleName().toString().contains("Builder")
                || (kind != ElementKind.CLASS && kind != ElementKind.INTERFACE && kind != ElementKind.ENUM);
    }

    String getDescription(DocTrees docTrees, TypeElement te) {
        DocCommentTree docCommentTree = docTrees.getDocCommentTree(te);
        if (docCommentTree != null) {
            Aggregator aggregator = new Aggregator();
            aggregator.addAll(docCommentTree.getFirstSentence());
            return ": " + aggregator.toString().replaceAll("#+ ", "").trim();
        }
        return "";
    }

    Path getOutputFile(QualifiedNameable element) throws IOException {
        Path outFile = currentResource = outputDirectory.resolve(qualifiedNameToPath(element));
        if (!Files.exists(outFile.getParent())) {
            Files.createDirectories(outFile.getParent());
        }
        reporter.print(Diagnostic.Kind.NOTE, "Writing " + outFile.toAbsolutePath());
        return outFile;
    }

    static TypeElement getSuperclassElement(TypeElement typeElement) {
        TypeMirror superclass = typeElement.getSuperclass();
        if (superclass.getKind().equals(TypeKind.NONE)) {
            return null;
        }
        if (superclass.toString().equals("java.lang.Object")) {
            return null;
        }
        if (superclass.toString().equals("java.lang.Record")) {
            return null;
        }
        return (TypeElement) ((DeclaredType) superclass).asElement();
    }

    static String qualifiedNameToPath(QualifiedNameable element) {
        return qualifiedNameToPath(element.getQualifiedName().toString());
    }

    static String qualifiedNameToPath(String reference) {
        if (reference.endsWith("qute")) {
            reference += ".README";
        }
        return reference
                .replace("dev.ebullient.convert.", "")
                .replace("tools.", "")
                .replace("qute.", "")
                .replace(".", "/")
                + ".md";
    }

    static interface MarkdownOption extends Option {
        String getValue();
    }

    class Aggregator {
        List<String> content = new ArrayList<>();
        Deque<HtmlElement> htmlEntity = new ArrayDeque<>();

        void addFullBody(DocCommentTree docCommentTree) {
            if (docCommentTree == null) {
                return;
            }
            addAll(docCommentTree.getFullBody());
        }

        void addAll(List<? extends DocTree> docTrees) {
            if (docTrees == null) {
                return;
            }
            for (DocTree docTree : docTrees) {
                add(docTree);
            }
        }

        void add(DocTree docTree) {
            switch (docTree.getKind()) {
                case TEXT:
                    add(((TextTree) docTree).getBody().toString().replace("\n", ""));
                    break;
                case CODE:
                case LITERAL:
                    add("`" + ((LiteralTree) docTree).getBody().toString() + "`");
                    break;
                case START_ELEMENT:
                    startEntity(docTree.toString());
                    break;
                case END_ELEMENT:
                    endEntity(docTree.toString());
                    break;
                case LINK:
                    LinkTree linkTree = (LinkTree) docTree;
                    String reference = linkTree.getReference().toString();

                    // look at label before anchor has been removed from reference
                    String label = linkTree.getLabel().toString();
                    if (label == null || label.isEmpty()) {
                        int classBegin = reference.lastIndexOf(".");
                        label = classBegin > -1 ? reference.substring(classBegin + 1) : reference;
                    }

                    // remove anchor from the class reference
                    String anchor = "";
                    int hash = reference.indexOf("#");
                    if (hash > -1) {
                        anchor = reference.substring(hash);
                        reference = reference.substring(0, hash);
                    }

                    // resolve the class reference to a path (and then make that link relative)
                    reference = qualifiedNameToPath(reference);
                    if (!reference.startsWith("http")) {
                        Path target = outputDirectory.resolve(reference);
                        Path relative = currentResource.getParent().relativize(target);
                        reference = relative.toString();
                    }
                    add(String.format("[%s](%s%s)", label, reference, anchor));
                    break;
                case ENTITY:
                    add(replacementFor(docTree.toString()));
                    break;
                default:
                    System.out.println("tree kind: " + docTree.getKind() + ", content: " + docTree);
                    break;
            }
        }

        void add(String text) {
            if (htmlEntity.isEmpty()) {
                content.add(text);
            } else {
                htmlEntity.peek().add(text);
            }
        }

        void startEntity(String text) {
            HtmlElement element = new HtmlElement(text);
            if (!htmlEntity.isEmpty()) {
                element.testOuter(htmlEntity.peek());
            }
            htmlEntity.push(element);
        }

        void endEntity(String text) {
            HtmlElement element = htmlEntity.pop();
            element.end(text);
            add(element.sanitize());
        }

        @Override
        public String toString() {
            while (!htmlEntity.isEmpty()) {
                endEntity("");
            }
            return String.join("", content)
                    .replaceAll("(?m) +$", "")
                    .replaceAll("</br/>", "")
                    .replace("\n\n\n", "\n\n")
                    .replaceAll("<br ?/?>", "  \n") // do this late.
                    .trim();
        }
    }

    static class HtmlElement {
        List<String> html = new ArrayList<>();
        String li = "";
        String indent = "";
        String tag;

        public HtmlElement(String text) {
            html.add(text);
            tag = text.replaceAll("<([^ >]+).*", "$1");
            if (tag.equalsIgnoreCase("ol")) {
                li = "1. ";
            } else if (tag.equalsIgnoreCase("ul")) {
                li = "- ";
            }
        }

        public void testOuter(HtmlElement peek) {
            boolean listBegin = tag.equalsIgnoreCase("ol") || tag.equalsIgnoreCase("ul");
            if (listBegin && peek != null && !peek.indent.isEmpty()) {
                indent = peek.indent + "  ";
            }
            if (tag.equals("li")) {
                li = peek == null ? "- " : peek.li;
            }
        }

        public void add(String text) {
            html.add(text);
        }

        public void end(String text) {
            if (text.isEmpty()) {
                html.add("</" + tag + ">");
            }
            html.add(text);
        }

        public String sanitize() {
            return String.join("", html)
                    .replaceAll("</?tt>", "`")
                    .replaceAll("</?b>", "**")
                    .replaceAll("</?i>", "*")
                    .replaceAll("<h1>", "# ")
                    .replaceAll("<h2>", "## ")
                    .replaceAll("<h3>", "### ")
                    .replaceAll("<(p|ol|ul)>\\s*", "\n\n")
                    .replaceAll("</(ol|ul|h.)>", "\n")
                    .replaceAll("</(li|p)>\\s*", "")
                    .replaceAll("<li>", "\n" + indent + li)
                    .replaceAll("<a href=\"(.*)\">(.*)</a>", "[$2]($1)");
        }
    }

    public static void main(String[] args) {
        String docletName = MarkdownDoclet.class.getName();

        String[] docletArgs = new String[] {
                "-doclet", docletName,
                "-docletpath", "target/classes/",
                "-sourcepath", "src/main/java/",
                "dev.ebullient.convert.qute",
                "dev.ebullient.convert.tools.dnd5e.qute",
                "dev.ebullient.convert.tools.pf2e.qute"
        };
        DocumentationTool docTool = ToolProvider.getSystemDocumentationTool();
        docTool.run(System.in, System.out, System.err, docletArgs);
    }

    private String replacementFor(String str) {
        switch (str) {
            case "&quot;":
                return "\"";
            case "&amp;":
                return "&";
            case "&#39;":
                return "'";
            case "&lt;":
                return "<";
            case "&gt;":
                return ">";
            default:
                return str;
        }
    }
}
