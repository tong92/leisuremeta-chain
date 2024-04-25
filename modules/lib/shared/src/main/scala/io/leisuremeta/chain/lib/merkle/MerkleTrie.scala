package io.leisuremeta.chain.lib
package merkle

import cats.Monad
import cats.data.{EitherT, Kleisli, StateT}
import cats.syntax.either.given
import cats.syntax.eq.given
import cats.syntax.traverse.given

import eu.timepit.refined.api.Refined
import eu.timepit.refined.refineV
import fs2.Stream
import scodec.bits.{BitVector, ByteVector}

import crypto.Hash.ops.*
import MerkleTrieNode.{Children, ChildrenCondition, MerkleHash}

import util.refined.bitVector.given

object MerkleTrie:

  type NodeStore[F[_]] =
    Kleisli[EitherT[F, String, *], MerkleHash, Option[MerkleTrieNode]]

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def get[F[_]: Monad: NodeStore](
      key: BitVector,
  ): StateT[EitherT[F, String, *], MerkleTrieState, Option[ByteVector]] =
    StateT.inspectF: (state: MerkleTrieState) =>
      if state.root.isEmpty then EitherT.rightT[F, String](None)
      else
        getNode[F](state).flatMap:
          case node if node.prefix.value === key =>
            EitherT.pure[F, String](node.getValue)
          case node
              if key.startsWith(node.prefix.value) &&
                key.size >= node.prefix.value.size + 4 =>
            val (index, nextKey) = key.drop(node.prefix.value.size).splitAt(4)
            val nextRootOption = node.getChildren.flatMap:
              _.value(index.toInt(signed = false))
            nextRootOption match
              case Some(nextRoot) =>
                get[F](nextKey) runA state.copy(root = Some(nextRoot))
              case None =>
                EitherT.rightT[F, String](None)
          case _ =>
            EitherT.rightT[F, String](None)

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def put[F[_]: Monad: NodeStore](
      key: BitVector,
      value: ByteVector,
  ): StateT[EitherT[F, String, *], MerkleTrieState, Unit] =
    StateT.modifyF: (state: MerkleTrieState) =>
      state.root match
        case None =>
          val leaf     = MerkleTrieNode.leaf(ensurePrefix(key), value)
          val leafHash = leaf.toHash
          EitherT.rightT[F, String]:
            state.copy(
              root = Some(leafHash),
              diff = state.diff.add(leafHash, leaf),
            )
        case Some(root) =>
          getNode[F](state).flatMap: (node: MerkleTrieNode) =>
            val prefix0: BitVector = node.prefix.value

            node match
              case MerkleTrieNode.Leaf(_, value0) =>
                val (commonPrefix, remainder0, remainder1) =
                  getCommonPrefixNibbleAndRemainders(prefix0, key)
                (remainder0.nonEmpty, remainder1.nonEmpty) match
                  case (false, false) =>
                    if value0 === value then EitherT.rightT[F, String](state)
                    else
                      val leaf1 =
                        MerkleTrieNode.leaf(ensurePrefix(prefix0), value)
                      val leaf1Hash = leaf1.toHash
                      EitherT.rightT[F, String]:
                        state.copy(
                          root = Some(leaf1Hash),
                          diff = state.diff
                            .remove(root, node)
                            .add(leaf1Hash, leaf1),
                        )
                  case (false, true) =>
                    val (index10, prefix10) = remainder1.splitAt(4)
                    val leaf1 =
                      MerkleTrieNode.leaf(ensurePrefix(prefix10), value)
                    val leaf1Hash = leaf1.toHash
                    val children: Children = Children.empty
                      .updated(index10.toInt(signed = false), Some(leaf1Hash))
                    val branch = MerkleTrieNode.branchWithData(
                      node.prefix,
                      children,
                      value0,
                    )
                    val branchHash = branch.toHash
                    EitherT.rightT[F, String]:
                      state.copy(
                        root = Some(branchHash),
                        diff = state.diff
                          .remove(root, node)
                          .add(branchHash, branch)
                          .add(leaf1Hash, leaf1),
                      )
                  case (true, false) =>
                    val (index00, prefix00) = remainder0.splitAt(4)
                    val leaf0 =
                      MerkleTrieNode.leaf(ensurePrefix(prefix00), value0)
                    val leaf0Hash = leaf0.toHash
                    val children: Children = Children.empty
                      .updated(index00.toInt(signed = false), Some(leaf0Hash))
                    val branch = MerkleTrieNode.branchWithData(
                      ensurePrefix(commonPrefix),
                      children,
                      value,
                    )
                    val branchHash = branch.toHash
                    EitherT.rightT[F, String]:
                      state.copy(
                        root = Some(branchHash),
                        diff = state.diff
                          .remove(root, node)
                          .add(branchHash, branch)
                          .add(leaf0Hash, leaf0),
                      )
                  case (true, true) =>
                    val (index00, prefix00) = remainder0.splitAt(4)
                    val leaf0 =
                      MerkleTrieNode.leaf(ensurePrefix(prefix00), value0)
                    val leaf0Hash           = leaf0.toHash
                    val (index10, prefix10) = remainder1.splitAt(4)
                    val leaf1 =
                      MerkleTrieNode.leaf(ensurePrefix(prefix10), value)
                    val leaf1Hash = leaf1.toHash
                    val children: Children = Children.empty
                      .updated(index00.toInt(signed = false), Some(leaf0Hash))
                      .updated(index10.toInt(signed = false), Some(leaf1Hash))
                    val branch = MerkleTrieNode.branch(
                      ensurePrefix(commonPrefix),
                      children,
                    )
                    val branchHash = branch.toHash
                    EitherT.rightT[F, String]:
                      state.copy(
                        root = Some(branchHash),
                        diff = state.diff
                          .remove(root, node)
                          .add(branchHash, branch)
                          .add(leaf0Hash, leaf0)
                          .add(leaf1Hash, leaf1),
                      )
              case _ =>
                EitherT
                  .fromOption[F](
                    node.getChildren,
                    s"Node has no children: $node",
                  )
                  .flatMap: children =>
                    val (commonPrefix, remainder0, remainder1) =
                      getCommonPrefixNibbleAndRemainders(prefix0, key)
                    (remainder0.nonEmpty, remainder1.nonEmpty) match
                      case (false, false) =>
                        node.getValue match
                          case Some(nodeValue) if nodeValue === value =>
                            EitherT.rightT[F, String](state)
                          case _ =>
                            val branch1     = node.setValue(value)
                            val branch1Hash = branch1.toHash
                            EitherT.rightT[F, String]:
                              state.copy(
                                root = Some(branch1Hash),
                                diff = state.diff
                                  .remove(root, node)
                                  .add(branch1Hash, branch1),
                              )
                      case (false, true) =>
                        val (index10, prefix10) = remainder1.splitAt(4)
                        children.value(index10.toInt(signed = false)) match
                          case None =>
                            val leaf1 =
                              MerkleTrieNode.leaf(ensurePrefix(prefix10), value)
                            val leaf1Hash = leaf1.toHash
                            EitherT
                              .fromEither:
                                refineV[ChildrenCondition]:
                                  children.value.updated(
                                    index10.toInt(signed = false),
                                    Some(leaf1Hash),
                                  )
                              .map: children1 =>
                                val branch1     = node.setChildren(children1)
                                val branch1Hash = branch1.toHash
                                state.copy(
                                  root = Some(branch1Hash),
                                  diff = state.diff
                                    .remove(root, node)
                                    .add(branch1Hash, branch1)
                                    .add(leaf1Hash, leaf1),
                                )
                          case Some(childHash) =>
                            put[F](prefix10, value)
                              .runS(state.copy(root = Some(childHash)))
                              .map: childState =>
//                                println(s"======> Child state: $childState")
                                val children1 = children.updated(
                                  index10.toInt(signed = false),
                                  childState.root,
                                )
                                val branch1     = node.setChildren(children1)
                                val branch1Hash = branch1.toHash
                                childState.copy(
                                  root = Some(branch1Hash),
                                  diff = childState.diff
                                    .remove(root, node)
                                    .add(branch1Hash, branch1),
                                )
                      case (true, false) =>
                        val (index00, prefix00) = remainder0.splitAt(4)
                        val child0     = node.setPrefix(ensurePrefix(prefix00))
                        val child0Hash = child0.toHash
                        val children1 = MerkleTrieNode.Children.empty.updated(
                          index00.toInt(signed = false),
                          Some(child0Hash),
                        )
                        val branch1 = MerkleTrieNode.branchWithData(
                          ensurePrefix(commonPrefix),
                          children1,
                          value,
                        )
                        val branch1Hash = branch1.toHash
                        EitherT.rightT[F, String]:
                          state.copy(
                            root = Some(branch1Hash),
                            diff = state.diff
                              .remove(root, node)
                              .add(branch1Hash, branch1)
                              .add(child0Hash, child0),
                          )
                      case (true, true) =>
                        val (index00, prefix00) = remainder0.splitAt(4)
                        val (index10, prefix10) = remainder1.splitAt(4)
                        val child0     = node.setPrefix(ensurePrefix(prefix00))
                        val child0Hash = child0.toHash
                        val child1 =
                          MerkleTrieNode.leaf(ensurePrefix(prefix10), value)
                        val child1Hash = child1.toHash
                        val children1 = Children.empty
                          .updated(
                            index00.toInt(signed = false),
                            Some(child0Hash),
                          )
                          .updated(
                            index10.toInt(signed = false),
                            Some(child1Hash),
                          )
                        val branch1 = MerkleTrieNode.branch(
                          ensurePrefix(commonPrefix),
                          children1,
                        )
                        val branch1Hash = branch1.toHash
                        EitherT.rightT[F, String]:
                          state.copy(
                            root = Some(branch1Hash),
                            diff = state.diff
                              .remove(root, node)
                              .add(branch1Hash, branch1)
                              .add(child0Hash, child0)
                              .add(child1Hash, child1),
                          )

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def remove[F[_]: Monad: NodeStore](
      key: BitVector,
  ): StateT[EitherT[F, String, *], MerkleTrieState, Boolean] =
    StateT: (state: MerkleTrieState) =>
      state.root.fold(EitherT.pure[F, String]((state, false))): root =>
        getNode[F](state).flatMap: node =>
          node match
            case MerkleTrieNode.Leaf(prefix, _) if prefix.value === key =>
              val state1 =
                state.copy(root = None, diff = state.diff.remove(root, node))
              EitherT.rightT[F, String]((state1, true))
            case MerkleTrieNode.Leaf(_, _) =>
              EitherT.pure[F, String]((state, false))
            case MerkleTrieNode.BranchWithData(prefix, children, value)
                if prefix.value === key =>
              val branch1: MerkleTrieNode =
                MerkleTrieNode.Branch(prefix, children)
              val branch1Hash = branch1.toHash

              val state1 = state.copy(
                root = Some(branch1Hash),
                diff = state.diff.remove(root, node).add(branch1Hash, branch1),
              )

              EitherT.rightT[F, String]((state1, true))

            case _ if !key.startsWith(node.prefix.value) =>
              EitherT.pure[F, String]((state, false))

            case _ =>
              val prefix      = node.prefix
              val valueOption = node.getValue

              val remainder1 =
                getCommonPrefixNibbleAndRemainders(prefix.value, key)._3

              if remainder1.size < 4L then
                EitherT.pure[F, String]((state, false))
              else
                val (index1, key1) = remainder1 splitAt 4L

                EitherT
                  .fromOption(node.getChildren, s"No children for $node")
                  .flatMap: children =>
                    children.value(index1.toInt(signed = false)) match
                      case None =>
                        EitherT.pure[F, String]((state, false))
                      case Some(childHash) =>
                        remove[F](key1)
                          .run(state.copy(root = Some(childHash)))
                          .subflatMap:
                            case (_, false) =>
                              ((state, false)).asRight[String]
                            case (childState, true)
                                if childState.root.isEmpty
                                  && children.value.count(_.nonEmpty) <= 1
                                  && node.getValue.isEmpty =>
                              val childState1 = childState.copy(
                                root = None,
                                diff = childState.diff.remove(root, node),
                              )
                              ((childState1, true)).asRight[String]
                            case (childState, true) =>
                              val refined = refineV[ChildrenCondition]:
                                children.value.updated(
                                  index1.toInt(signed = false),
                                  childState.root,
                                )
                              refined.map: children1 =>
                                val branch = valueOption match
                                  case None =>
                                    MerkleTrieNode.branch(prefix, children1)
                                  case Some(value) =>
                                    MerkleTrieNode.branchWithData(
                                      prefix,
                                      children1,
                                      value,
                                    )
                                val branchHash = branch.toHash
                                val childState1 = childState.copy(
                                  root = Some(branchHash),
                                  diff = childState.diff
                                    .remove(root, node)
                                    .add(branchHash, branch),
                                )
                                (childState1, true)

  type ByteStream[F[_]] = Stream[EitherT[F, String, *], (BitVector, ByteVector)]

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  def from[F[_]: Monad: NodeStore](
      key: BitVector,
  ): StateT[EitherT[F, String, *], MerkleTrieState, ByteStream[F]] =
    StateT.inspectF: (state: MerkleTrieState) =>
      state.root match
        case None => EitherT.rightT[F, String](Stream.empty)
        case Some(_) =>
          getNode[F](state).flatMap:
            case MerkleTrieNode.Leaf(prefix, value) =>
              if key <= prefix.value then
                EitherT.pure(Stream.emit((prefix.value, value)))
              else EitherT.rightT[F, String](Stream.empty)
            case node =>
              val prefix      = node.prefix
              val valueOption = node.getValue

              def runFrom(key: BitVector)(
                  hashWithIndex: (Option[MerkleHash], Int),
              ): EitherT[F, String, ByteStream[F]] =
                from(key)
                  .runA(state.copy(root = hashWithIndex._1))
                  .map:
                    _.map: (key, a) =>
                      val key1 = prefix.value
                        ++ BitVector.fromInt(hashWithIndex._2, 4) ++ key
                      (key1, a)

              def flatten(enums: List[ByteStream[F]]): ByteStream[F] =
                enums.foldLeft[ByteStream[F]](Stream.empty)(_ ++ _)

              EitherT
                .fromOption(node.getChildren, s"No children for $node")
                .flatMap: children =>
                  if key <= prefix.value then
                    scribe.debug:
                      s"======>[Case #1] key: $key, prefix: ${prefix.value}"
                    val initialValue: ByteStream[F] = valueOption match
                      case None =>
                        Stream.empty
                      case Some(bytes) =>
                        Stream.eval(EitherT.pure((prefix.value, bytes)))
                    children.value.toList.zipWithIndex
                      .traverse(runFrom(BitVector.empty))
                      .map(flatten)
                      .map(initialValue ++ _)
                  else if prefix.value.nonEmpty && !key.startsWith(prefix.value)
                  then
                    scribe.debug:
                      s"======>[Case #2] prefix: ${prefix.value}, key: $key"
                    EitherT.rightT[F, String](Stream.empty)
                  else
                    val (index1, key1) = key drop prefix.value.size splitAt 4L
                    scribe.debug(
                      s"======>[Case #3] index1: $index1, key1: $key1",
                    )
                    val targetChildren: List[(Option[MerkleHash], Int)] =
                      children.value.toList.zipWithIndex
                        .drop(index1.toInt(signed = false))
                    targetChildren match
                      case Nil => EitherT.rightT[F, String](Stream.empty)
                      case x :: xs =>
                        for
                          headList <- runFrom(key1)(x)
                          tailList <- xs.traverse(runFrom(BitVector.empty))
                        yield headList ++ flatten(tailList)

  def getNode[F[_]: Monad](
      state: MerkleTrieState,
  )(using ns: NodeStore[F]): EitherT[F, String, MerkleTrieNode] = for
    root <- EitherT.fromOption[F](
      state.root,
      s"Cannot get node from empty merkle trie: $state",
    )
    node <- state.diff
      .get(root)
      .fold:
        ns.run(root)
          .subflatMap:
            _.toRight(s"Merkle trie node $root is not found: $state")
      .apply(EitherT.pure(_))
  yield
    scribe.debug(s"Accessing node ${state.root} -> $node")
    node

  private def getCommonPrefixNibbleAndRemainders(
      bits0: BitVector,
      bits1: BitVector,
  ): (BitVector, BitVector, BitVector) =
    val commonPrefixNibbleSize: Int = (bits0 ^ bits1)
      .grouped(4L)
      .takeWhile(_ === BitVector.low(4L))
      .size
    val nextPrefixBitSize          = commonPrefixNibbleSize.toLong * 4L
    val remainder0                 = bits0 drop nextPrefixBitSize
    val (commonPrefix, remainder1) = bits1 splitAt nextPrefixBitSize
    (commonPrefix, remainder0, remainder1)

  type PrefixBits = BitVector Refined MerkleTrieNode.PrefixCondition

  @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
  def ensurePrefix(bits: BitVector): PrefixBits =
    refineV[MerkleTrieNode.PrefixCondition](bits).toOption.get

  extension (bits: BitVector)
    def <=(that: BitVector): Boolean =
      val thisBytes = bits.bytes
      val thatBytes = that.bytes
      val minSize   = thisBytes.size min thatBytes.size

      (0L `until` minSize)
        .find: i =>
          thisBytes.get(i) =!= thatBytes.get(i)
        .fold(bits.size <= that.size): i =>
          thisBytes.get(i) <= thatBytes.get(i)

end MerkleTrie
