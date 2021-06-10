package cpw.mods.niofs.union;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class UnionFileSystem extends FileSystem {
    private final UnionPath root = new UnionPath(this, UnionPath.ROOT);
    private final UnionFileSystemProvider provider;
    private final List<Path> basepaths;
    private final Map<Path,EmbeddedFileSystemMetadata> embeddedFileSystems;
    private record EmbeddedFileSystemMetadata(Path path, FileSystem fs) {}

    public UnionFileSystem(final UnionFileSystemProvider provider, final Path... basepaths) {
        this.provider = provider;
        this.basepaths = IntStream.range(0, basepaths.length)
                .mapToObj(i->basepaths[basepaths.length - i - 1]).toList(); // we flip the list so later elements are first in search order.
        this.embeddedFileSystems = this.basepaths.stream().filter(path -> !Files.isDirectory(path))
                .map(UnionFileSystem::openFileSystem)
                .flatMap(Optional::stream)
                .collect(Collectors.toMap(EmbeddedFileSystemMetadata::path, Function.identity()));
    }

    private static Optional<EmbeddedFileSystemMetadata> openFileSystem(final Path path) {
        try {
            return Optional.of(new EmbeddedFileSystemMetadata(path, FileSystems.newFileSystem(path)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public UnionFileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return Collections.singletonList(root);
    }

    public Path getRoot() {
        return root;
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return Collections::emptyIterator;
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return Set.of("basic");
    }

    @Override
    public Path getPath(final String first, final String... more) {
        return new UnionPath(this, first+this.getSeparator()+String.join(this.getSeparator(), more));
    }

    @Override
    public PathMatcher getPathMatcher(final String syntaxAndPattern) {
        throw new UnsupportedOperationException();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException();
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException();
    }

    List<Path> getBasePaths() {
        return this.basepaths;
    }

    private Optional<BasicFileAttributes> getFileAttributes(final Path path) {
        try {
            return Optional.of(path.getFileSystem().provider().readAttributes(path, BasicFileAttributes.class));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Optional<Path> findFirstPathAt(final UnionPath path) {
        return this.basepaths.stream()
                .map(p->unionPath(p, path))
                .filter(Files::exists)
                .findFirst();
    }

    private <T> Stream<T> streamPathList(final Function<Path,Optional<T>> function) {
        return this.basepaths.stream()
                .map(function)
                .flatMap(Optional::stream);
    }

    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(final UnionPath path, final Class<A> type, final LinkOption... options) throws IOException {
        if (type == BasicFileAttributes.class) {
            return (A) findFirstPathAt(path)
                    .flatMap(this::getFileAttributes)
                    .orElseThrow(() -> new FileNotFoundException("Missing path "+path));
        } else {
            throw new UnsupportedOperationException();
        }
    }

    public void checkAccess(final UnionPath p, final AccessMode... modes) throws IOException {
        try {
            findFirstPathAt(p).ifPresentOrElse(path-> {
                try {
                    path.getFileSystem().provider().checkAccess(path, modes);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, ()->{
                throw new UncheckedIOException("No file found", new FileNotFoundException());
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private Path unionPath(final Path basePath, final UnionPath path) {
        var embeddedpath = path.toString();
        var resolvepath = embeddedpath.length() > 1 && path.isAbsolute() ? path.toString().substring(1) : embeddedpath;
        if (embeddedFileSystems.containsKey(basePath)) {
            return embeddedFileSystems.get(basePath).fs().getPath(resolvepath);
        } else {
            return basePath.resolve(resolvepath);
        }
    }

    public SeekableByteChannel newReadByteChannel(final UnionPath path) throws IOException {
        try {
            return findFirstPathAt(path)
                    .map(this::byteChannel)
                    .orElseThrow(FileNotFoundException::new);
        } catch (UncheckedIOException ioe) {
            throw ioe.getCause();
        }
    }


    private SeekableByteChannel byteChannel(final Path path) {
        try {
            return Files.newByteChannel(path, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public DirectoryStream<Path> newDirStream(final UnionPath path, final DirectoryStream.Filter<? super Path> filter) throws IOException {
        final var allpaths = new LinkedHashSet<Path>();
        for (final var bp : basepaths) {
            final var dir = unionPath(bp, path);
            if (!Files.exists(dir)) continue;
            final var ds = Files.newDirectoryStream(dir, filter);
            StreamSupport.stream(ds.spliterator(), false).
                    map(other -> (embeddedFileSystems.containsKey(bp)? other : bp.relativize(other)).toString()).
                    map(this::getPath).
                    forEachOrdered(allpaths::add);
        }
        return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
                return allpaths.iterator();
            }

            @Override
            public void close() throws IOException {
                // noop
            }
        };
    }
}