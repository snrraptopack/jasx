package jasx.lsp;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;

/**
 * Every name written in a Java file, where it is: identifiers, the names after dots, and the names that
 * declarations introduce (classes, methods, fields, locals, parameters). Hover finds the name under the
 * cursor among them ({@link JavaHovers}).
 *
 * <p>javac's tree API gives the start and end of each tree, not of the name inside a declaration, so a
 * declaration's name is found as the first whole word equal to it after the part that comes before it (the
 * modifiers, the type). Positions are offsets in the text javac parsed: for a {@code .jasx} file, its
 * generated Java, which the caller maps back to the {@code .jasx} file.
 */
final class JavaNames {
    private JavaNames() {}

    /**
     * A name: the tree it belongs to, and {@code [start, end)} in the parsed text.
     *
     * @param declaration whether the name is introduced here (a declaration) rather than used
     */
    record Name(TreePath path, String name, int start, int end, boolean declaration) {}

    /** Every name in {@code unit}, whose text is {@code text}. */
    static List<Name> in(CompilationUnitTree unit, String text, Trees trees) {
        SourcePositions positions = trees.getSourcePositions();
        List<Name> names = new ArrayList<>();
        new TreePathScanner<Void, Void>() {
            private long start(Tree tree) {
                return positions.getStartPosition(unit, tree);
            }

            private long end(Tree tree) {
                return positions.getEndPosition(unit, tree);
            }

            private void add(String name, long start, boolean declaration) {
                if (start >= 0 && !name.isEmpty()) {
                    names.add(new Name(getCurrentPath(), name, (int) start, (int) start + name.length(), declaration));
                }
            }

            /** A declaration's name: the first whole word {@code name} at or after {@code from}. */
            private void addDeclared(String name, long from) {
                if (from >= 0) {
                    add(name, wordAt(text, name, (int) from), true);
                }
            }

            @Override
            public Void visitIdentifier(IdentifierTree tree, Void unused) {
                add(tree.getName().toString(), start(tree), false);
                return super.visitIdentifier(tree, unused);
            }

            @Override
            public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
                String name = tree.getIdentifier().toString();
                add(name, end(tree) - name.length(), false);
                return super.visitMemberSelect(tree, unused);
            }

            @Override
            public Void visitMemberReference(MemberReferenceTree tree, Void unused) {
                String name = tree.getName().toString();
                if (!name.equals("<init>")) { // Foo::new
                    add(name, end(tree) - name.length(), false);
                }
                return super.visitMemberReference(tree, unused);
            }

            @Override
            public Void visitClass(ClassTree tree, Void unused) {
                long from = tree.getModifiers() != null && end(tree.getModifiers()) > 0
                        ? end(tree.getModifiers()) : start(tree);
                addDeclared(tree.getSimpleName().toString(), from);
                return super.visitClass(tree, unused);
            }

            @Override
            public Void visitMethod(MethodTree tree, Void unused) {
                boolean constructor = tree.getName().contentEquals("<init>");
                if (!constructor && tree.getReturnType() != null) {
                    addDeclared(tree.getName().toString(), end(tree.getReturnType()));
                } else if (constructor && getCurrentPath().getParentPath().getLeaf() instanceof ClassTree owner) {
                    // A constructor is written with its class's name; javac's element for it is the constructor.
                    long afterModifiers = end(tree.getModifiers()) > 0 ? end(tree.getModifiers()) : start(tree);
                    String name = owner.getSimpleName().toString();
                    if (afterModifiers >= 0) {
                        add(name, wordAt(text, name, (int) afterModifiers), false);
                    }
                }
                return super.visitMethod(tree, unused);
            }

            @Override
            public Void visitVariable(VariableTree tree, Void unused) {
                Tree type = tree.getType();
                // An implicit lambda parameter or var: no type written, the name starts the tree.
                long typeEnd = type != null ? end(type) : -1;
                addDeclared(tree.getName().toString(), typeEnd > start(tree) ? typeEnd : start(tree));
                return super.visitVariable(tree, unused);
            }

            @Override
            public Void visitTypeParameter(TypeParameterTree tree, Void unused) {
                add(tree.getName().toString(), start(tree), true);
                return super.visitTypeParameter(tree, unused);
            }
        }.scan(unit, null);
        return names;
    }

    /** The first whole word {@code word} in {@code text} at or after {@code from}, or {@link Diagnostic#NOPOS}. */
    static long wordAt(String text, String word, int from) {
        for (int at = text.indexOf(word, from); at >= 0; at = text.indexOf(word, at + 1)) {
            boolean startsWord = at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1));
            int after = at + word.length();
            boolean endsWord = after == text.length() || !Character.isJavaIdentifierPart(text.charAt(after));
            if (startsWord && endsWord) {
                return at;
            }
        }
        return Diagnostic.NOPOS;
    }
}
