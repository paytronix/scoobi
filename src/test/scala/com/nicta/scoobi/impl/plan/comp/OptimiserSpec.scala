package com.nicta.scoobi
package impl
package plan
package comp

import org.specs2.matcher.DataTables
import org.scalacheck.{Gen, Arbitrary, Prop}
import org.kiama.rewriting._
import core._
import testing.mutable.UnitSpecification

class OptimiserSpec extends UnitSpecification with DataTables with CompNodeData {

  "1. Nodes must be flattened" >> {
    "1.1 A Flatten Node which is an input to 2 other nodes must be copied to each node" >> new nodes {
      optimise(flattenSplit, parallelDo(f1), parallelDo(f1)) must beLike {
        case ParallelDo1(ff1) :: ParallelDo1(ff2) :: _  => nodesAreDistinct(ff1, ff2)
      }
      optimise(flattenSplit, parallelDo(f1), parallelDo(f1), parallelDo(f1)) must beLike {
        case ParallelDo1(ff1) :: ParallelDo1(ff2)  :: ParallelDo1(ff3) :: _  => nodesAreDistinct(ff1, ff2, ff3)
      }
    }
    "1.2 A Flatten Node which is an input to a ParallelDo then replicate the ParallelDo on each of the Flatten inputs" >> new nodes {
      optimise(flattenSink, parallelDo(flattens)) must beLike {
        case List(Flatten1(ParallelDo1(ll1) :: ParallelDo1(ll2) :: _))  => (l1 === ll1) and (l2 === ll2)
      }
    }
    "1.2.1 the Flatten Node must have the same manifest type as the output of the parallel nodes" >> new nodes {
      optimise(flattenSink, parallelDo(flattens)) must beLike {
        case List(fl @ Flatten1((pd @ ParallelDo1(ll1)) :: ParallelDo1(ll2) :: _))  =>
          fl.mr.mwf === pd.mr.mwf
      }
    }
    "1.3 A Flatten Node with Flatten inputs must collect all the inner inputs" >> new nodes {
      "input"                                       | "expected"                                    |>
      flatten(l1)                                   ! flatten(l1)                                   |
      flatten(flatten(l1))                          ! flatten(l1)                                   |
      flatten(flatten(flatten(l1)))                 ! flatten(l1)                                   |
      flatten(flatten(l1), l1)                      ! flatten(l1, l1)                               |
      flatten(l1, flatten(l1))                      ! flatten(l1, l1)                               |
      flatten(l1, flatten(l1), l2)                  ! flatten(l1, l1, l2)                           |
      flatten(l1, l2, flatten(flatten(l1)))         ! flatten(l1, l2, l1)                           |
      flatten(l1, l2, flatten(l1, pd(rt), l1), l2)  ! flatten(l1, l2, l1, pd(rt), l1, l2)           | { (input, output) =>
        showStructure(optimise(flattenFuse, input).head) ==== showStructure(output)
      }

      check(Prop.forAll { (node: CompNode) =>
        collectNestedFlatten(optimise(flattenFuse, node).head) must beEmpty
      })
    }
  }

  "2. Combines must be optimised" >> {
    "A Combine which doesn't have a GroupByKey as an Input must be transformed to a ParallelDo" >> new nodes {
      "input"                                        | "expected"                                    |
       cb(l1)                                        ! pd(l1)                                        |
       cb(pd(l1))                                    ! pd(pd(l1))                                    |
       cb(gbk(l1))                                   ! cb(gbk(l1))                                   |> { (input, output) =>
         showStructure(optimise(combineToParDo, input).head) ==== showStructure(output)
       }
    }
    "Any optimised Combine in the graph can only have GroupByKey as an input" >> prop { (node: CompNode, f: factory) => {}; import f._
      forall(collectCombine(optimise(combineToParDo, node).head)) { n =>
        n must beLike { case Combine1(GroupByKey1(_)) => ok }
      }
    }
    "After optimisation, all the transformed Combines must be ParallelDo" >> prop { (node: CompNode, f: factory) => import f._
      val optimised = optimise(combineToParDo, node).head
      (collectCombine(node).size + collectParallelDo(node).size) ===
      (collectCombineGbk(optimised).size + collectParallelDo(optimised).size)
    }
  }

  "3. Successive ParallelDos must be fused" >> prop { (node: CompNode, f: factory) => import f._
    val optimised = optimise(parDoFuse(pass = 1), node).head
    collectSuccessiveParDos(optimised) must beEmpty
  };p

  "4. GroupByKeys" >> {
    "4.1 the GroupByKey is replicated so that it can not be the input of different nodes  " >> prop { (node: CompNode, f: factory) => import f._
      val optimised = optimise(groupByKeySplit, node).head

      // collects the gbks, they must form a set and not a bag
      val before = collectGroupByKey(node).map(_.id)
      val after  = collectGroupByKey(optimised).map(_.id)
      before.size must be_>=(after.size)
      after.size must_== after.toSet.size
    }

    "4.2 if the input of a GroupByKey is a Flatten, the Flatten is also replicated" >> prop { (node: CompNode, f: factory) => import f._
      val optimised = optimise(groupByKeySplit, node).head

      // collects the flattens inside GroupByKey, they must form a set and not a bag
      val flattens = collectGBKFlatten(optimised).map(_.id)
      flattens.size must_== flattens.toSet.size
    }

    "4.3 examples" >> new nodes {
      optimise(groupByKeySplit, parallelDo(gbk1), parallelDo(gbk1)) must beLike {
        case ParallelDo1(ggbk1) :: ParallelDo1(ggbk2) :: _  => nodesAreDistinct(ggbk1, ggbk2)
      }
      optimise(groupByKeySplit, parallelDo(gbk1), parallelDo(gbk1), parallelDo(gbk1)) must beLike {
        case ParallelDo1(ggbk1) :: ParallelDo1(ggbk2)  :: ParallelDo1(ggbk3) :: _  => nodesAreDistinct(ggbk1, ggbk2, ggbk3)
      }
      optimise(groupByKeySplit, flatten(gbkf1), flatten(gbkf1), flatten(gbkf1)) must beLike {
        case Flatten1((ggbk1 @ GroupByKey1(ff1))::_) :: Flatten1((ggbk2 @ GroupByKey1(ff2))::_)  :: Flatten1((ggbk3 @ GroupByKey1(ff3))::_) :: _  =>
          nodesAreDistinct(ggbk1, ggbk2, ggbk3) and nodesAreDistinct(ff1, ff2, ff3)
      }
    }
  }

  "5. Remaining Combine nodes (without GroupByKey inputs)" >> {
    "5.1 the remaining Combine nodes must be replicated so that one Combine can not be the input of different nodes" >> prop { (node: CompNode, f: factory) => import f._
      val optimised = optimise(combineSplit, node).head

      // collects the combine nodes, they must form a set and not a bag
      val before = collectCombine(node).map(_.id)
      val after = collectCombine(optimised).map(_.id)
      before.size must be_>=(after.size)
      before.size must_== after.toSet.size
    }

    "5.2 examples" >> new nodes {
      optimise(combineSplit, parallelDo(combine1), parallelDo(combine1)) must beLike {
        case ParallelDo1(c1) :: ParallelDo1(c2) :: _  => nodesAreDistinct(c1, c2)
      }
      optimise(combineSplit, parallelDo(combine1), parallelDo(combine1), parallelDo(combine1)) must beLike {
        case ParallelDo1(c1) :: ParallelDo1(c2)  :: ParallelDo1(c3) :: _  => nodesAreDistinct(c1, c2, c3)
      }
    }
  }

  "6. ParallelDos which are outputs of the graph must be marked with a fuseBarrier" >> {
    "6.1 with a random graph" >> prop { (node: CompNode, out: List[CompNode], f: factory) => import f._
      val optimised = optimise(parDoFuseBarrier(out), node).head
      // collects the flatten nodes which are leaves. If they are in the outputs set
      // their fuseBarrier must be true
      val inOutputs: Seq[CompNode] = collectParallelDo(optimised).filter(out.contains)
      def fuseBarrier: CompNode => Boolean = { case pd: ParallelDo[_,_,_] => pd.fuseBarrier }
      forall(inOutputs){ pd => pd must beTrue ^^ fuseBarrier }
    }
  }
//  "7. One parallelDo node shared with 2 other parallelDo nodes must fuse into one parallelDo node and 2 Project Nodes" >> new nodes {
//    val pd1 = pd(load)
//    val (pd2, pd3) = (pd(pd1), pd(pd1))
//
//    optimise(fuseSharedParDo(flatten(root(pd2, pd3))))
//  }

  trait nodes extends factory with Optimiser with CompNodes {
    lazy val (l1, l2)   = (load, load)
    lazy val f1         = flatten(l1)
    lazy val flattens   = flatten(l1, l2)
    lazy val gbk1       = gbk(l1)
    lazy val mt1        = mt(gbk1)
    lazy val gbkf1      = gbk(f1)
    lazy val combine1   = cb(l1)
    lazy val combine2   = cb(l2)

    def collectNestedFlatten = collectl {
      case f @ Flatten1(ins) if ins exists isFlatten => f
    }
    def nodesAreDistinct(nodes: CompNode*) = nodes.distinct.size aka nodes.mkString(", ") must_== nodes.size

  }


  implicit def arbitraryFactory: Arbitrary[factory] = Arbitrary(Gen.value(new factory{}))
}
