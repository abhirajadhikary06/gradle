/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.file;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class DefaultFileHierarchySet {
    private static final EmptyFileHierarchySet EMPTY = new EmptyFileHierarchySet();

    /**
     * Creates an empty set.
     */
    public static FileHierarchySet of() {
        return EMPTY;
    }

    /**
     * Creates a set containing the given directory and all its descendants
     */
    public static FileHierarchySet of(final File rootDir) {
        return new PrefixFileSet(rootDir);
    }

    /**
     * Creates a set containing the given directory and all its descendants
     */
    public static FileHierarchySet fromPath(final String absolutePath) {
        return new PrefixFileSet(absolutePath);
    }

    /**
     * Creates a set containing the given directories and all their descendants.
     */
    public static FileHierarchySet of(Iterable<File> rootDirs) {
        FileHierarchySet set = EMPTY;
        for (File rootDir : rootDirs) {
            set = set.plus(rootDir);
        }
        return set;
    }

    /**
     * Creates a set containing the given directories and all their descendants.
     */
    public static FileHierarchySet fromPaths(Iterable<String> rootDirAbsolutePaths) {
        FileHierarchySet set = EMPTY;
        for (String rootDirAbsolutePath : rootDirAbsolutePaths) {
            set = set.plus(rootDirAbsolutePath);
        }
        return set;
    }

    private static class EmptyFileHierarchySet implements FileHierarchySet {
        @Override
        public boolean contains(File file) {
            return false;
        }

        @Override
        public boolean contains(String path) {
            return false;
        }

        @Override
        public FileHierarchySet plus(File rootDir) {
            return new PrefixFileSet(rootDir);
        }

        @Override
        public FileHierarchySet plus(String absolutePath) {
            return new PrefixFileSet(absolutePath);
        }

        @Override
        public void visitRoots(RootVisitor visitor) {
        }

        @Override
        public String toString() {
            return "EMPTY";
        }
    }

    private static class PrefixFileSet implements FileHierarchySet {
        private final Node rootNode;

        PrefixFileSet(File rootDir) {
            String path = toPath(rootDir);
            this.rootNode = new Node(path);
        }

        PrefixFileSet(String rootPath) {
            String path = removeTrailingSeparator(rootPath);
            this.rootNode = new Node(path);
        }

        PrefixFileSet(Node rootNode) {
            this.rootNode = rootNode;
        }

        @VisibleForTesting
        List<String> flatten() {
            final List<String> prefixes = new ArrayList<String>();
            rootNode.visitHierarchy(0, new HierarchyVisitor() {
                @Override
                public void visitNode(int depth, Node node) {
                    if (depth == 0) {
                        prefixes.add(node.prefix);
                    } else {
                        prefixes.add(depth + ":" + node.prefix.replace(File.separatorChar, '/'));
                    }

                }
            });
            return prefixes;
        }

        @Override
        public boolean contains(String path) {
            return rootNode.contains(path, 0);
        }

        @Override
        public boolean contains(File file) {
            return rootNode.contains(file.getPath(), 0);
        }

        @Override
        public FileHierarchySet plus(File rootDir) {
            return new PrefixFileSet(rootNode.plus(toPath(rootDir)));
        }

        @Override
        public FileHierarchySet plus(String absolutePath) {
            return new PrefixFileSet(rootNode.plus(removeTrailingSeparator(absolutePath)));
        }

        private static String toPath(File rootDir) {
            assert rootDir.isAbsolute();
            String absolutePath = rootDir.getAbsolutePath();
            return removeTrailingSeparator(absolutePath);
        }

        private static String removeTrailingSeparator(String absolutePath) {
            if (absolutePath.equals("/")) {
                absolutePath = "";
            } else if (absolutePath.endsWith(File.separator)) {
                absolutePath = absolutePath.substring(0, absolutePath.length() - 1);
            }
            return absolutePath;
        }

        @Override
        public void visitRoots(final RootVisitor visitor) {
            final Deque<String> prefixStack = new ArrayDeque<String>();
            rootNode.visitHierarchy(0, new HierarchyVisitor() {
                @Override
                public void visitNode(int depth, Node node) {
                    while (prefixStack.size() > depth) {
                        prefixStack.pop();
                    }
                    if (node.children.isEmpty()) {
                        String root;
                        if (prefixStack.isEmpty()) {
                            root = node.prefix;
                        } else {
                            StringBuilder builder = new StringBuilder();
                            for (String prefix : prefixStack) {
                                builder.append(prefix);
                                builder.append(File.separatorChar);
                            }
                            builder.append(node.prefix);
                            root = builder.toString();
                        }
                        visitor.visitRoot(root);
                    } else {
                        prefixStack.add(node.prefix);
                    }
                }
            });
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder();
            rootNode.visitHierarchy(0, new HierarchyVisitor() {
                private boolean first = true;

                @Override
                public void visitNode(int depth, Node node) {
                    if (first) {
                        first = false;
                    } else {
                        builder.append("\n");
                    }
                    builder.append(Strings.repeat(" ", depth * 2));
                    builder.append(node.prefix);
                }
            });
            return builder.toString();
        }
    }

    private static class Node {
        private final String prefix;
        private final List<Node> children;

        Node(String prefix) {
            this.prefix = prefix;
            this.children = ImmutableList.of();
        }

        public Node(String prefix, List<Node> children) {
            this.prefix = prefix;
            this.children = children;
        }

        Node plus(String path) {
            int maxPos = Math.min(prefix.length(), path.length());
            int prefixLen = sizeOfCommonPrefix(path, 0);
            if (prefixLen == maxPos) {
                if (prefix.length() == path.length()) {
                    // Path == prefix
                    if (children.isEmpty()) {
                        return this;
                    }
                    return new Node(path);
                }
                if (prefix.length() < path.length()) {
                    // Path is a descendant of this
                    if (children.isEmpty()) {
                        return this;
                    }
                    int startNextSegment = prefixLen == 0 ? 0 : prefixLen + 1;
                    List<Node> merged = new ArrayList<Node>(children.size() + 1);
                    boolean matched = false;
                    for (Node child : children) {
                        if (child.sizeOfCommonPrefix(path, startNextSegment) > 0) {
                            // TODO - we've already calculated the common prefix and calling plus() will calculate it again
                            merged.add(child.plus(path.substring(startNextSegment)));
                            matched = true;
                        } else {
                            merged.add(child);
                        }
                    }
                    if (!matched) {
                        merged.add(new Node(path.substring(startNextSegment)));
                    }
                    return new Node(prefix, merged);
                } else {
                    // Path is an ancestor of this
                    return new Node(path);
                }
            }
            String commonPrefix = prefix.substring(0, prefixLen);

            int newChildrenStartIndex = prefixLen == 0 ? 0 : prefixLen + 1;

            Node newThis = new Node(prefix.substring(newChildrenStartIndex), children);
            Node sibling = new Node(path.substring(newChildrenStartIndex));
            return new Node(commonPrefix, ImmutableList.of(newThis, sibling));
        }

        int sizeOfCommonPrefix(String path, int offset) {
            return FilePathUtil.sizeOfCommonPrefix(prefix, path, offset);
        }

        /**
         * This uses an optimized version of {@link String#regionMatches(int, String, int, int)}
         * which does not check for negative indices or integer overflow.
         */
        boolean isChildOfOrThis(String filePath, int offset) {
            if (prefix.isEmpty()) {
                return true;
            }

            int pathLength = filePath.length();
            int prefixLength = prefix.length();
            int endOfThisSegment = prefixLength + offset;
            if (pathLength < endOfThisSegment) {
                return false;
            }
            for (int i = prefixLength - 1, j = endOfThisSegment - 1; i >= 0; i--, j--) {
                if (prefix.charAt(i) != filePath.charAt(j)) {
                    return false;
                }
            }
            return endOfThisSegment == pathLength || filePath.charAt(endOfThisSegment) == File.separatorChar;
        }

        boolean contains(String filePath, int offset) {
            if (!isChildOfOrThis(filePath, offset)) {
                return false;
            }
            if (children.isEmpty()) {
                return true;
            }

            int startNextSegment = prefix.isEmpty() ? offset : offset + prefix.length() + 1;
            for (Node child : children) {
                if (child.contains(filePath, startNextSegment)) {
                    return true;
                }
            }
            return false;
        }

        public void visitHierarchy(int depth, HierarchyVisitor visitor) {
            visitor.visitNode(depth, this);
            for (Node child : children) {
                child.visitHierarchy(depth + 1, visitor);
            }
        }

        @Override
        public String toString() {
            return prefix;
        }
    }

    private interface HierarchyVisitor {
        void visitNode(int depth, Node node);
    }
}
