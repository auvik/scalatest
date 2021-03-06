/*
 * Copyright 2001-2013 Artima, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.scalatest.enablers

import org.scalautils.Equality
import org.scalatest.words.ArrayWrapper
import scala.collection.GenTraversable
import org.scalatest.FailureMessages
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import Aggregating.tryEquality

/**
 * Supertrait for typeclasses that enable the <code>be</code> <code>sorted</code> matcher syntax.
 *
 * <p>
 * A <code>Sequencing[S]</code> provides access to the "sortable nature" of type <code>S</code> in such
 * a way that <code>be</code> <code>sorted</code> matcher syntax can be used with type <code>S</code>. An <code>S</code>
 * can be any type for which the concept of being sorted makes sense, such as sequences. ScalaTest provides
 * implicit implementations for several types. You can enable the <code>be</code> <code>sorted</code> matcher syntax on your own
 * type <code>U</code> by defining a <code>Sequencing[U}</code> for the type and making it available implicitly.
 * 
 * <p>
 * ScalaTest provides an implicit <code>Sequencing</code> instance for <code>scala.collection.GenSeq</code>
 * in the <code>Aggregating</code> companion object.
 * </p>
 */
trait Sequencing[S] {

  /**
   * Implements <code>contain</code> <code>inOrder</code> syntax for sequences of type <code>S</code>.
   *
   * @param sequence an sequence about which an assertion is being made
   * @param eles elements all of which should be contained, in order of appearance in <code>eles</code>, in the passed sequence
   * @return true if the passed sequence contains all of the passed elements in (iteration) order
   */
  def containsInOrder(sequence: S, eles: Seq[Any]): Boolean

  /**
   * Implements <code>contain</code> <code>inOrderOnly</code> syntax for sequences of type <code>S</code>.
   *
   * @param sequence an sequence about which an assertion is being made
   * @param eles the only elements that should be contained, in order of appearence in <code>eles</code>, in the passed sequence
   * @return true if the passed sequence contains only the passed elements in (iteration) order
   */
  def containsInOrderOnly(sequence: S, eles: Seq[Any]): Boolean

  /**
   * Implements <code>contain</code> <code>theSameElementsInOrderAs</code> syntax for sequences of type <code>S</code>.
   *
   * @param leftSequence an sequence about which an assertion is being made
   * @param rightSequence an sequence that should contain the same elements, in (iterated) order as the passed <code>leftSequence</code>
   * @return true if the passed <code>leftSequence</code> contains the same elements, in (iterated) order, as the passed <code>rightSequence</code>
   */
  def containsTheSameElementsInOrderAs(leftSequence: S, rightSequence: GenTraversable[Any]): Boolean
}

object Sequencing {
  
  private def checkTheSameElementsInOrderAs[T](left: GenTraversable[T], right: GenTraversable[Any], equality: Equality[T]): Boolean = {
    @tailrec
    def checkEqual(left: Iterator[T], right: Iterator[Any]): Boolean = {
      if (left.hasNext && right.hasNext) {
        val nextLeft = left.next
        val nextRight = right.next
        if (!equality.areEqual(nextLeft, nextRight))
          false
        else
          checkEqual(left, right)
      }
      else
        left.isEmpty && right.isEmpty
    }
    checkEqual(left.toIterator, right.toIterator)
  }

  private def checkInOrderOnly[T](left: GenTraversable[T], right: GenTraversable[Any], equality: Equality[T]): Boolean = {
  
    @tailrec
    def checkEqual(left: T, right: Any, leftItr: Iterator[T], rightItr: Iterator[Any]): Boolean = {
      if (equality.areEqual(left, right)) { // The first time in, left must equal right
        // Now need to iterate through the left while it is equal to the right
        @tailrec
        def checkNextLeftAgainstCurrentRight(): Option[T] = { // Returns first left that doesn't match the current right, or None, if all remaining lefts matched current right
          if (leftItr.hasNext) {
            val nextLeft = leftItr.next
            if (equality.areEqual(nextLeft, right))
              checkNextLeftAgainstCurrentRight()
            else
              Some(nextLeft)
          }
          else None // No more lefts
        }
        val nextLeftOption = checkNextLeftAgainstCurrentRight()
        nextLeftOption match {
          case Some(nextLeft) => 
            if (rightItr.hasNext) {
              checkEqual(nextLeft, rightItr.next, leftItr, rightItr)
            }
            else false
          case None => !rightItr.hasNext // No more lefts remaining, so we're good so long as no more rights remaining either.
        }
      }
      else false
    }

    val leftItr: Iterator[T] = left.toIterator
    val rightItr: Iterator[Any] = right.toIterator
    if (leftItr.hasNext && rightItr.hasNext)
      checkEqual(leftItr.next, rightItr.next, leftItr, rightItr)
    else left.isEmpty && right.isEmpty
  }
  
  private def checkInOrder[T](left: GenTraversable[T], right: GenTraversable[Any], equality: Equality[T]): Boolean = {
    @tailrec
    def lastIndexOf(itr: Iterator[T], element: Any, idx: Option[Int], i: Int): Option[Int] = {
      if (itr.hasNext) {
        val next = itr.next
        if (equality.areEqual(next, element))
          lastIndexOf(itr, element, Some(i), i + 1)
        else
          lastIndexOf(itr, element, idx, i + 1)
      }
      else
        idx
    }
  
    @tailrec
    def checkEqual(left: GenTraversable[T], rightItr: Iterator[Any], processedSet: Set[Any]): Boolean = {
      if (rightItr.hasNext) {
        val nextRight = rightItr.next
        if (processedSet.find(tryEquality(_, nextRight, equality)).isDefined)
          throw new IllegalArgumentException(FailureMessages("inOrderDuplicate", nextRight))
        lastIndexOf(left.toIterator, nextRight, None, 0) match {
          case Some(idx) => 
            checkEqual(left.drop(idx).tail, rightItr, processedSet + nextRight)
          case None => 
            false // Element not found, let's fail early
        }
      }
      else // No more element in right, left contains all of right.
        true
    }
    checkEqual(left, right.toIterator, Set.empty)
  }

  implicit def sequencingNatureOfGenSeq[E, SEQ[_] <: scala.collection.GenSeq[_]](implicit equality: Equality[E]): Sequencing[SEQ[E]] =
    new Sequencing[SEQ[E]] {

      def containsInOrder(seq: SEQ[E], elements: scala.collection.Seq[Any]): Boolean = {
        checkInOrder(seq.asInstanceOf[GenTraversable[E]], elements, equality)
      }

      def containsInOrderOnly(seq: SEQ[E], elements: scala.collection.Seq[Any]): Boolean = {
        checkInOrderOnly[E](seq.asInstanceOf[GenTraversable[E]], elements, equality)
      }

// TODO: Make elements a Sequencing
      def containsTheSameElementsInOrderAs(seq: SEQ[E], elements: GenTraversable[Any]): Boolean = {
        checkTheSameElementsInOrderAs[E](seq.asInstanceOf[GenTraversable[E]], elements, equality)
      }
    }

  // Enables (xs should contain ("HI")) (after being lowerCased)
  implicit def convertEqualityToGenSeqSequencing[E, SEQ[_] <: scala.collection.GenSeq[_]](equality: Equality[E]): Sequencing[SEQ[E]] = 
    sequencingNatureOfGenSeq(equality)

  implicit def sequencingNatureOfIterator[E, IT[_] <: scala.collection.Iterator[_]](implicit equality: Equality[E]): Sequencing[IT[E]] =
    new Sequencing[IT[E]] {

      def containsInOrder(it: IT[E], elements: scala.collection.Seq[Any]): Boolean = {
        checkInOrder(it.asInstanceOf[Iterator[E]].toVector, elements, equality) // TODO: Don't like converting iterator to vector here.
      }

      def containsInOrderOnly(it: IT[E], elements: scala.collection.Seq[Any]): Boolean = {
        checkInOrderOnly[E](it.asInstanceOf[Iterator[E]].toVector, elements, equality)
      }

// TODO: Make elements a Sequencing
      def containsTheSameElementsInOrderAs(it: IT[E], elements: GenTraversable[Any]): Boolean = {
        checkTheSameElementsInOrderAs[E](it.asInstanceOf[Iterator[E]].toVector, elements, equality)
      }
    }

  // Enables (xs should contain ("HI")) (after being lowerCased)
  implicit def convertEqualityToIteratorSequencing[E, IT[_] <: scala.collection.Iterator[_]](equality: Equality[E]): Sequencing[IT[E]] = 
    sequencingNatureOfIterator(equality)

  implicit def sequencingNatureOfArray[E](implicit equality: Equality[E]): Sequencing[Array[E]] = 
    new Sequencing[Array[E]] {

      def containsInOrder(array: Array[E], elements: scala.collection.Seq[Any]): Boolean = {
        checkInOrder(new ArrayWrapper(array), elements, equality)
      }

      def containsInOrderOnly(array: Array[E], elements: scala.collection.Seq[Any]): Boolean = {
        checkInOrderOnly(new ArrayWrapper(array), elements, equality)
      }

      def containsTheSameElementsInOrderAs(array: Array[E], elements: GenTraversable[Any]): Boolean = {
        checkTheSameElementsInOrderAs[E](new ArrayWrapper(array), elements, equality)
      }
    }

  // Enables (xs should contain ("HI")) (after being lowerCased)
  implicit def convertEqualityToArraySequencing[E](equality: Equality[E]): Sequencing[Array[E]] = 
    sequencingNatureOfArray(equality)

  implicit def sequencingNatureOfJavaList[E, JLIST[_] <: java.util.List[_]](implicit equality: Equality[E]): Sequencing[JLIST[E]] = 
    new Sequencing[JLIST[E]] {

      def containsInOrder(col: JLIST[E], elements: scala.collection.Seq[Any]): Boolean = {
        checkInOrder(col.asInstanceOf[java.util.List[E]].asScala, elements, equality)
      }

      def containsInOrderOnly(col: JLIST[E], elements: scala.collection.Seq[Any]): Boolean = {
        checkInOrderOnly(col.asInstanceOf[java.util.List[E]].asScala, elements, equality)
      }

      def containsTheSameElementsInOrderAs(col: JLIST[E], elements: GenTraversable[Any]): Boolean = {
        checkTheSameElementsInOrderAs(col.asInstanceOf[java.util.List[E]].asScala, elements, equality)
      }
    }

  implicit def convertEqualityToJavaListSequencing[E, JLIST[_] <: java.util.List[_]](equality: Equality[E]): Sequencing[JLIST[E]] = 
    sequencingNatureOfJavaList(equality)

  implicit def sequencingNatureOfString(implicit equality: Equality[Char]): Sequencing[String] = 
    new Sequencing[String] {

      def containsInOrder(s: String, elements: scala.collection.Seq[Any]): Boolean = {
        checkInOrder(s, elements, equality)
      }

      def containsInOrderOnly(s: String, elements: scala.collection.Seq[Any]): Boolean = {
        checkInOrderOnly(s, elements, equality)
      }

      def containsTheSameElementsInOrderAs(s: String, elements: GenTraversable[Any]): Boolean = {
        checkTheSameElementsInOrderAs(s, elements, equality)
      }
    }

  implicit def convertEqualityToStringSequencing(equality: Equality[Char]): Sequencing[String] = 
    sequencingNatureOfString(equality)
    
}
