package io.github.loganalyzer.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Разворачивает пользовательские пути в список файлов логов.
 * Поддерживает: конкретный файл, каталог (с рекурсией) и glob-шаблон вида {@code logs/*.log}.
 */
public final class InputCollector {

    /** Маски файлов, используемые при обходе каталога, если пользователь не задал свои. */
    public static final List<String> DEFAULT_INCLUDES = List.of("*.log", "*.txt", "*.json", "*.log.*");

    private InputCollector() {
    }

    /**
     * @param inputs    пути из командной строки
     * @param includes  маски имён файлов внутри каталогов, например {@code *.log}
     * @param recursive обходить подкаталоги
     * @return отсортированный список существующих файлов
     */
    public static List<Path> collect(List<String> inputs, List<String> includes, boolean recursive) {
        List<Path> files = new ArrayList<>();
        for (String input : inputs) {
            if (input == null || input.isBlank()) {
                continue;
            }
            if (containsGlob(input)) {
                files.addAll(expandGlob(input));
                continue;
            }
            Path path = Path.of(input);
            if (Files.isDirectory(path)) {
                files.addAll(listDirectory(path, includes, recursive));
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            } else {
                throw new IllegalArgumentException("Путь не найден: " + input);
            }
        }
        return files.stream().distinct().sorted(Comparator.comparing(Path::toString)).toList();
    }

    private static boolean containsGlob(String input) {
        return input.indexOf('*') >= 0 || input.indexOf('?') >= 0;
    }

    private static List<Path> expandGlob(String pattern) {
        Path asPath = Path.of(pattern);
        Path base = asPath.getParent();
        // Ищем самую глубокую часть пути без метасимволов — от неё и обходим
        while (base != null && containsGlob(base.toString())) {
            base = base.getParent();
        }
        Path root = base == null ? Path.of(".") : base;
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> matcher.matches(p) || matcher.matches(p.normalize()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось раскрыть шаблон " + pattern, e);
        }
    }

    private static List<Path> listDirectory(Path dir, List<String> includes, boolean recursive) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String include : includes) {
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + include));
        }
        int depth = recursive ? Integer.MAX_VALUE : 1;
        try (Stream<Path> stream = Files.walk(dir, depth)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> matchers.isEmpty()
                            || matchers.stream().anyMatch(m -> m.matches(p.getFileName())))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать каталог " + dir, e);
        }
    }
}
