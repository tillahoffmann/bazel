// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.remote;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputFileCache;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.exec.SingleBuildFileCache;
import com.google.devtools.build.lib.remote.RemoteProtocol.ContentDigest;
import com.google.devtools.build.lib.remote.RemoteProtocol.FileNode;
import com.google.devtools.build.lib.remote.TreeNodeRepository.TreeNode;
import com.google.devtools.build.lib.testutil.Scratch;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystem.HashFunction;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.ArrayList;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link TreeNodeRepository}. */
@RunWith(JUnit4.class)
public class TreeNodeRepositoryTest {
  private Scratch scratch;
  private Root rootDir;
  private Path rootPath;

  @Before
  public final void setRootDir() throws Exception {
    FileSystem.setDigestFunctionForTesting(HashFunction.SHA1);
    scratch = new Scratch();
    rootDir = Root.asDerivedRoot(scratch.dir("/exec/root"));
    rootPath = rootDir.getPath();
  }

  private TreeNodeRepository createTestTreeNodeRepository() {
    ActionInputFileCache inputFileCache = new SingleBuildFileCache(
        rootPath.getPathString(), scratch.getFileSystem());
    return new TreeNodeRepository(rootPath, inputFileCache);
  }

  @Test
  @SuppressWarnings("ReferenceEquality")
  public void testSubtreeReusage() throws Exception {
    Artifact fooCc = new Artifact(scratch.file("/exec/root/a/foo.cc"), rootDir);
    Artifact fooH = new Artifact(scratch.file("/exec/root/a/foo.h"), rootDir);
    Artifact bar = new Artifact(scratch.file("/exec/root/b/bar.txt"), rootDir);
    Artifact baz = new Artifact(scratch.file("/exec/root/c/baz.txt"), rootDir);
    TreeNodeRepository repo = createTestTreeNodeRepository();
    TreeNode root1 = repo.buildFromActionInputs(ImmutableList.<ActionInput>of(fooCc, fooH, bar));
    TreeNode root2 = repo.buildFromActionInputs(ImmutableList.<ActionInput>of(fooCc, fooH, baz));
    // Reusing same node for the "a" subtree.
    assertThat(
            root1.getChildEntries().get(0).getChild() == root2.getChildEntries().get(0).getChild())
        .isTrue();
  }

  @Test
  public void testMerkleDigests() throws Exception {
    Artifact foo = new Artifact(scratch.file("/exec/root/a/foo", "1"), rootDir);
    Artifact bar = new Artifact(scratch.file("/exec/root/a/bar", "11"), rootDir);
    TreeNodeRepository repo = createTestTreeNodeRepository();
    TreeNode root = repo.buildFromActionInputs(ImmutableList.<ActionInput>of(foo, bar));
    TreeNode aNode = root.getChildEntries().get(0).getChild();
    TreeNode fooNode = aNode.getChildEntries().get(1).getChild(); // foo > bar in sort order!
    TreeNode barNode = aNode.getChildEntries().get(0).getChild();

    repo.computeMerkleDigests(root);
    ImmutableCollection<ContentDigest> digests = repo.getAllDigests(root);
    ContentDigest rootDigest = repo.getMerkleDigest(root);
    ContentDigest aDigest = repo.getMerkleDigest(aNode);
    ContentDigest fooDigest = repo.getMerkleDigest(fooNode);
    ContentDigest fooContentsDigest = ContentDigests.computeDigest(foo.getPath());
    ContentDigest barDigest = repo.getMerkleDigest(barNode);
    ContentDigest barContentsDigest = ContentDigests.computeDigest(bar.getPath());
    assertThat(digests)
        .containsExactly(
            rootDigest, aDigest, barDigest, barContentsDigest, fooDigest, fooContentsDigest);

    ArrayList<FileNode> fileNodes = new ArrayList<>();
    ArrayList<ActionInput> actionInputs = new ArrayList<>();
    repo.getDataFromDigests(digests, actionInputs, fileNodes);
    assertThat(actionInputs).containsExactly(bar, foo);
    assertThat(fileNodes).hasSize(4);
    FileNode rootFileNode = fileNodes.get(0);
    assertThat(rootFileNode.getChild(0).getPath()).isEqualTo("a");
    assertThat(rootFileNode.getChild(0).getDigest()).isEqualTo(aDigest);
    FileNode aFileNode = fileNodes.get(1);
    assertThat(aFileNode.getChild(0).getPath()).isEqualTo("bar");
    assertThat(aFileNode.getChild(0).getDigest()).isEqualTo(barDigest);
    assertThat(aFileNode.getChild(1).getPath()).isEqualTo("foo");
    assertThat(aFileNode.getChild(1).getDigest()).isEqualTo(fooDigest);
    FileNode barFileNode = fileNodes.get(2);
    assertThat(barFileNode.getFileMetadata().getDigest()).isEqualTo(barContentsDigest);
    FileNode fooFileNode = fileNodes.get(3);
    assertThat(fooFileNode.getFileMetadata().getDigest()).isEqualTo(fooContentsDigest);
  }

  @Test
  public void testGetAllDigests() throws Exception {
    Artifact foo1 = new Artifact(scratch.file("/exec/root/a/foo", "1"), rootDir);
    Artifact foo2 = new Artifact(scratch.file("/exec/root/b/foo", "1"), rootDir);
    Artifact foo3 = new Artifact(scratch.file("/exec/root/c/foo", "1"), rootDir);
    TreeNodeRepository repo = createTestTreeNodeRepository();
    TreeNode root = repo.buildFromActionInputs(ImmutableList.<ActionInput>of(foo1, foo2, foo3));
    repo.computeMerkleDigests(root);
    // Reusing same node for the "foo" subtree: only need the root, root child, foo, and contents:
    assertThat(repo.getAllDigests(root)).hasSize(4);
  }

  @Test
  public void testNullArtifacts() throws Exception {
    Artifact foo = new Artifact(scratch.file("/exec/root/a/foo", "1"), rootDir);
    SortedMap<PathFragment, ActionInput> inputs = new TreeMap<>();
    inputs.put(foo.getExecPath(), foo);
    inputs.put(PathFragment.create("a/bar"), null);
    TreeNodeRepository repo = createTestTreeNodeRepository();
    TreeNode root = repo.buildFromActionInputs(inputs);
    repo.computeMerkleDigests(root);

    TreeNode aNode = root.getChildEntries().get(0).getChild();
    TreeNode fooNode = aNode.getChildEntries().get(1).getChild(); // foo > bar in sort order!
    TreeNode barNode = aNode.getChildEntries().get(0).getChild();
    ImmutableCollection<ContentDigest> digests = repo.getAllDigests(root);
    ContentDigest rootDigest = repo.getMerkleDigest(root);
    ContentDigest aDigest = repo.getMerkleDigest(aNode);
    ContentDigest fooDigest = repo.getMerkleDigest(fooNode);
    ContentDigest fooContentsDigest = ContentDigests.computeDigest(foo.getPath());
    ContentDigest barDigest = repo.getMerkleDigest(barNode);
    ContentDigest barContentsDigest = ContentDigests.computeDigest(new byte[0]);
    assertThat(digests)
        .containsExactly(
            rootDigest, aDigest, barDigest, barContentsDigest, fooDigest, fooContentsDigest);
  }

  @Test
  public void testEmptyTree() throws Exception {
    SortedMap<PathFragment, ActionInput> inputs = new TreeMap<>();
    TreeNodeRepository repo = createTestTreeNodeRepository();
    TreeNode root = repo.buildFromActionInputs(inputs);
    repo.computeMerkleDigests(root);

    assertThat(root.getChildEntries()).isEmpty();
  }
}
