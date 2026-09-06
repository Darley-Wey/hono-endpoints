package io.github.darleywey.honoendpoints.analysis;

import com.intellij.lang.javascript.psi.JSCallExpression;
import com.intellij.lang.javascript.psi.JSExpression;
import com.intellij.lang.javascript.psi.JSLiteralExpression;
import com.intellij.lang.javascript.psi.JSNewExpression;
import com.intellij.lang.javascript.psi.JSRecursiveWalkingElementVisitor;
import com.intellij.lang.javascript.psi.JSReferenceExpression;
import com.intellij.lang.javascript.psi.JSVariable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import io.github.darleywey.honoendpoints.model.HonoEndpoint;
import io.github.darleywey.honoendpoints.model.HonoEndpointGroup;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Initial PSI-based Hono route scanner.
 *
 * <p>This deliberately supports only routes whose path is a string literal and whose router can be
 * resolved back to a local {@code new Hono()} expression. Cross-file {@code route()} composition
 * and {@code basePath()} path propagation belong to the route-graph layer planned for the next milestone.</p>
 */
public final class HonoProjectScanner {
    private static final Set<String> SOURCE_EXTENSIONS = Set.of("js", "jsx", "mjs", "cjs", "ts", "tsx", "mts", "cts");
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete", "options", "head");
    private static final Set<String> CHAIN_METHODS = Set.of(
            "get", "post", "put", "patch", "delete", "options", "head",
            "all", "on", "route", "basePath", "use", "mount", "notFound", "onError"
    );

    private HonoProjectScanner() {}

    public static @NotNull List<HonoEndpointGroup> scanProject(@NotNull Project project) {
        var groups = new ArrayList<HonoEndpointGroup>();
        var psiManager = PsiManager.getInstance(project);
        var fileIndex = ProjectRootManager.getInstance(project).getFileIndex();

        fileIndex.iterateContent(file -> {
            if (!isJavaScriptOrTypeScript(file)) {
                return true;
            }

            PsiFile psiFile = psiManager.findFile(file);
            if (psiFile == null || !looksLikeHonoFile(psiFile)) {
                return true;
            }

            List<HonoEndpoint> endpoints = scanFile(psiFile);
            if (!endpoints.isEmpty()) {
                groups.add(new HonoEndpointGroup(psiFile, List.copyOf(endpoints)));
            }
            return true;
        });

        return List.copyOf(groups);
    }

    public static @NotNull List<HonoEndpoint> scanFile(@NotNull PsiFile file) {
        if (!looksLikeHonoFile(file)) {
            return List.of();
        }

        var endpoints = new ArrayList<HonoEndpoint>();
        file.accept(new JSRecursiveWalkingElementVisitor() {
            @Override
            public void visitJSCallExpression(@NotNull JSCallExpression call) {
                super.visitJSCallExpression(call);

                JSExpression methodExpression = call.getMethodExpression();
                if (!(methodExpression instanceof JSReferenceExpression methodReference)) {
                    return;
                }

                String method = methodReference.getReferenceName();
                if (method == null) {
                    return;
                }

                method = method.toLowerCase(Locale.ROOT);
                if (!HTTP_METHODS.contains(method)) {
                    return;
                }

                JSExpression qualifier = methodReference.getQualifier();
                if (!isHonoRouterExpression(qualifier, new HashSet<>())) {
                    return;
                }

                JSExpression[] arguments = call.getArguments();
                if (arguments.length == 0 || !(arguments[0] instanceof JSLiteralExpression pathLiteral)) {
                    return;
                }

                String path = pathLiteral.getStringValue();
                if (path == null || !path.startsWith("/")) {
                    return;
                }

                endpoints.add(new HonoEndpoint(method.toUpperCase(Locale.ROOT), path, call));
            }
        });

        return endpoints;
    }

    private static boolean isHonoRouterExpression(JSExpression expression, Set<PsiElement> visited) {
        if (expression == null || !visited.add(expression)) {
            return false;
        }

        if (expression instanceof JSNewExpression newExpression) {
            JSExpression constructor = newExpression.getMethodExpression();
            return constructor instanceof JSReferenceExpression reference
                    && "Hono".equals(reference.getReferenceName());
        }

        if (expression instanceof JSReferenceExpression reference) {
            PsiElement resolved = reference.resolve();
            if (resolved instanceof JSVariable variable && visited.add(resolved)) {
                return isHonoRouterExpression(variable.getInitializer(), visited);
            }
            return false;
        }

        if (expression instanceof JSCallExpression call) {
            JSExpression methodExpression = call.getMethodExpression();
            if (!(methodExpression instanceof JSReferenceExpression reference)) {
                return false;
            }

            String method = reference.getReferenceName();
            return method != null
                    && CHAIN_METHODS.contains(method)
                    && isHonoRouterExpression(reference.getQualifier(), visited);
        }

        return false;
    }

    private static boolean looksLikeHonoFile(PsiFile file) {
        String text = file.getText();
        return text.contains("from 'hono'")
                || text.contains("from \"hono\"")
                || text.contains("from 'hono/")
                || text.contains("from \"hono/")
                || text.contains("require('hono')")
                || text.contains("require(\"hono\")");
    }

    private static boolean isJavaScriptOrTypeScript(VirtualFile file) {
        String extension = file.getExtension();
        return extension != null && SOURCE_EXTENSIONS.contains(extension.toLowerCase(Locale.ROOT));
    }
}
