/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.uima.cas.impl;

import java.lang.reflect.Array;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Collector;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.CASRuntimeException;
import org.apache.uima.cas.FSIndex;
import org.apache.uima.cas.FSIterator;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.SelectFSs;
import org.apache.uima.cas.Type;
import org.apache.uima.cas.impl.Subiterator.BoundsUse;
import org.apache.uima.cas.text.AnnotationFS;
import org.apache.uima.cas.text.AnnotationIndex;
import org.apache.uima.jcas.cas.EmptyFSList;
import org.apache.uima.jcas.cas.FSArray;
import org.apache.uima.jcas.cas.FSList;
import org.apache.uima.jcas.cas.NonEmptyFSList;
import org.apache.uima.jcas.cas.TOP;
import org.apache.uima.jcas.impl.JCasImpl;
import org.apache.uima.jcas.tcas.Annotation;

/**
 * Collection of builder style methods to specify selection of FSs from indexes
 * shift handled in this routine
 * Comment codes:
 *   AI = implies AnnotationIndex
 *   
 * Iterator varieties and impl
 * 
 *   bounded?  type      order not unambig? strict? skipEq    
 *             Priority? Needed? 
 *     no
 *   coveredBy
 *   covering
 *   sameas
 *   
 *   for not-bounded, 
 *     - ignore strict and skipEq
 *       -- except: preceding implies skipping annotations whose end &gt; positioning begin
 *     - order-not-needed only applies if iicp size &gt; 1
 *     - unambig ==&gt; use Subiterator
 *         -- subiterator wraps: according to typePriority and order-not-needed
 *     - no Type Priority - need to pass in as arg to fsIterator_multiple_indexes
 *        == if no type priority, need to prevent rattling off the == type while compare is equal
 *        == affects both FsIterator_aggregation_common and FsIterator_subtypes_ordered
 *   for 3 other boundings:
 *     - use subiterator, pass in strict and skipeq
 *     
   finish this javadoc comment edit
 *   T extends FeatureStructure, not TOP, because of ref from FSIndex 
 *      which uses FeatureStructure for backwards compatibility                  
 */
public class SelectFSs_impl <T extends FeatureStructure> implements SelectFSs<T> {
  
  private final static boolean IS_UNORDERED = true;
  private final static boolean IS_ORDERED = false;
  private final static boolean IS_UNAMBIGUOUS = false;
  private final static boolean IS_NOT_STRICT = false;
  
  private CASImpl view;
  private JCasImpl jcas;
  private LowLevelIndex<T> index; 
  private TypeImpl ti;
  private int shift; 
  private int limit = -1;
  
  private FeatureStructure[] sourceFSArray = null;  // alternate source
  private FSList  sourceFSList  = null;  // alternate source
  
  private boolean isTypePriority = false;
//  private boolean isPositionUsesType = false; // REMOVED see https://issues.apache.org/jira/browse/UIMA-5536 
  private boolean isSkipSameBeginEndType = false; // for boundsUse only
  private boolean isNonOverlapping = IS_UNAMBIGUOUS;
  private boolean isIncludeAnnotBeyondBounds = false;
  private boolean isAllViews = false;
  private boolean isNullOK = false;
  private boolean isUnordered = false;
  private boolean isBackwards = false;
  private boolean isFollowing = false;
  private boolean isPreceding = false;
  
  private boolean isAltSource = false;
  
  private BoundsUse boundsUse = null; 
  
  private TOP startingFs = null; // this is used for non-annotation positioning too
  private AnnotationFS boundingFs = null;
  private boolean isEmptyBoundingFs = false;
  
  
  /* **********************************************
   * Constructors
   *   always need the cas
   *   might also have the type
   * Caller will convert other forms for the cas (e.g. jcas) 
   * and type (e.g. type name, MyType.type, MyType.class) to 
   * these arg forms.
   ************************************************/
  public SelectFSs_impl(CAS cas) {
    this.view = (CASImpl) cas.getLowLevelCAS();
    this.jcas = (JCasImpl) view.getJCas();
  }
  
  public SelectFSs_impl(FSArray source) {
    this(source._casView);
    isAltSource = true;
    sourceFSArray = source._getTheArray();
  }
  
  public SelectFSs_impl(FeatureStructure[] source, CAS cas) {
    this(cas);
    isAltSource = true;
    sourceFSArray = source;
  } 
    
  public SelectFSs_impl(FSList source) {
    this(source._casView);
    isAltSource = true;
    sourceFSList = source;
  }

  /************************************************
   * Builders
   ************************************************/
  /**
   * INDEX
   * If not specified, defaults to all FSs (orderNotNeeded) unless AnnotationIndex implied
   * @param indexName -
   * @return -
   */
  public SelectFSs_impl<T> index(String indexName) {
    this.index = view.indexRepository.getIndex(indexName);
    return this;
  }
  
  public SelectFSs_impl<T> index(FSIndex<T> aIndex) {
    this.index =  (LowLevelIndex<T>) aIndex;
    return this;
  }

  /*
   * TYPE
   * if not specified defaults to the index's uppermost type.  
   */
  
  public <N extends T> SelectFSs_impl<N> type(Type uimaType) {
    this.ti = (TypeImpl) uimaType;
    return (SelectFSs_impl<N>) this;
  }
  
  public <N extends T> SelectFSs_impl<N> type(String fullyQualifiedTypeName) {
    this.ti = view.getTypeSystemImpl().getType(fullyQualifiedTypeName);
    return (SelectFSs_impl<N>) this;
  }

  public <N extends T> SelectFSs_impl<N> type(int jcasClass_dot_type) {
    this.ti = (TypeImpl) view.getJCas().getCasType(jcasClass_dot_type);
    return (SelectFSs_impl<N>) this;
  }

  public <N extends T> SelectFSs_impl<N> type(Class<N> jcasClass_dot_class) {
    this.ti = (TypeImpl) view.getJCasImpl().getCasType(jcasClass_dot_class);
    return (SelectFSs_impl<N>) this;
  }  
  
  /* (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#typePriority()
   */
  @Override
  public SelectFSs<T> typePriority() {
    this.isTypePriority = true;
    return this;
  }

  /* (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#typePriority(boolean)
   */
  @Override
  public SelectFSs<T> typePriority(boolean aTypePriority) {
    this.isTypePriority = aTypePriority;
    return this;
  }

  /*********************************
   * boolean operations
   *********************************/
  
  // REMOVED see https://issues.apache.org/jira/browse/UIMA-5536
//  /* (non-Javadoc)
//   * @see org.apache.uima.cas.SelectFSs#positionUsesType()
//   */
//  @Override
//  public SelectFSs<T> positionUsesType() {
//    this.isPositionUsesType = true;
//    return this;
//  }
//
//  /* (non-Javadoc)
//   * @see org.apache.uima.cas.SelectFSs#positionUsesType(boolean)
//   */
//  @Override
//  public SelectFSs<T> positionUsesType(boolean aPositionUsesType) {
//    this.isPositionUsesType = aPositionUsesType;
//    return this;
//  }

  /* (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#skipEquals()
   */
  @Override
  public SelectFSs<T> skipWhenSameBeginEndType() {
    this.isSkipSameBeginEndType = true;
    return this;
  }

  /* (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#skipEquals(boolean)
   */
  @Override
  public SelectFSs<T> useAnnotationEquals(boolean useAnnotationEquals) {
    this.isSkipSameBeginEndType = useAnnotationEquals;
    return this;
  }

  /**
   * Filters while iterating
   **/
  
  @Override
  public SelectFSs_impl<T> nonOverlapping() { // AI known as unambiguous
    this.isNonOverlapping = true;
    return this;
  }  
  @Override
  public SelectFSs_impl<T> nonOverlapping(boolean bNonOverlapping) { // AI
    this.isNonOverlapping = bNonOverlapping;
    return this;
  } 
  
  @Override
  public SelectFSs_impl<T> includeAnnotationsWithEndBeyondBounds() { // AI known as "not strict"
    isIncludeAnnotBeyondBounds = true;
    return this;
  }  
  @Override
  public SelectFSs_impl<T> includeAnnotationsWithEndBeyondBounds(boolean includeAnnotationsWithEndBeyondBounds) { // AI
    isIncludeAnnotBeyondBounds = includeAnnotationsWithEndBeyondBounds;
    return this;
  } 
  
//  public SelectFSs_impl<T> useTypePriorities() {
//    return this;
//  }
//  public SelectFSs_impl<T> useTypePriorities(boolean useTypePriorities) {
//    return this;
//  }
  
  /**
   * Miscellaneous
   **/
  
  @Override
  public SelectFSs_impl<T> allViews() {
    this.isAllViews = true;
    return this;
  }
  @Override
  public SelectFSs_impl<T> allViews(boolean bAllViews) {
    this.isAllViews = bAllViews;
    return this;
  }
  
  @Override
  public SelectFSs_impl<T> nullOK() { // applies to get() and single()
   this.isNullOK = true;
   return this;
  }  
  @Override
  public SelectFSs_impl<T> nullOK(boolean bNullOk) {  // applies to get() and single()
    this.isNullOK = bNullOk;
    return this;
  }
    
  @Override
  public SelectFSs_impl<T> orderNotNeeded() {   // ignored if not ordered index
    this.isUnordered = true;
    return this;
  }                
  @Override
  public SelectFSs_impl<T> orderNotNeeded(boolean bUnordered) { // ignored if not ordered index
    this.isUnordered = bUnordered;
    return this;
  } 
  
  @Override
  public SelectFSs_impl<T> backwards() { // ignored if not ordered index
    this.isBackwards = true;
    return this;
  }                  
  @Override
  public SelectFSs_impl<T> backwards(boolean bBackwards) { // ignored if not ordered index
    this.isBackwards = bBackwards;
    return this;
  } 
  
//  public SelectFSs_impl<T> noSubtypes() {
//    return this;
//  }
//  public SelectFSs_impl<T> noSubtypes(boolean noSubtypes) {
//    return this;
//  }

  /*********************************
   * starting position
   *********************************/
  
  @Override
  public SelectFSs_impl<T> shifted(int shiftAmount) {
    this.shift = shiftAmount;
    return this;
  }
  
  @Override
  public SelectFSs_impl<T> startAt(TOP fs) {  // Ordered
    this.startingFs = fs;
    return this;
  } 
  
  @Override
  public SelectFSs_impl<T> startAt(int begin, int end) {  // AI
    this.startingFs = makePosAnnot(begin, end);
    return this;
  } 
  
  @Override
  public SelectFSs_impl<T> startAt(TOP fs, int offset) {  // Ordered
    this.startingFs = fs;
    this.shift = offset;
    return this;
  } 
  @Override
  public SelectFSs_impl<T> startAt(int begin, int end, int offset) {  // AI
    this.startingFs = makePosAnnot(begin, end);
    this.shift = offset;
    return this;
  }  
  
  @Override
  public SelectFSs_impl<T> limit(int alimit) {
    if (alimit < 0) {
      throw new IllegalArgumentException("limit argument must be >= 0, but was " + alimit);
    }
    this.limit = alimit;
    return this;
  }
    
  /*********************************
   * subselection based on boundingFs
   *********************************/
  @Override
  public SelectFSs_impl<T> coveredBy(AnnotationFS fs) {       // AI
    boundsUse = BoundsUse.coveredBy;
    this.boundingFs = fs;
//    this.isIncludeAnnotWithEndBeyondBounds = false; //default
    return this;
  }
  
  @Override
  public SelectFSs_impl<T> coveredBy(int begin, int end) {       // AI
    boundsUse = BoundsUse.coveredBy;
    this.boundingFs = makePosAnnot(begin, end);
//    this.isIncludeAnnotWithEndBeyondBounds = true; //default
    return this;
  }

  @Override
  public SelectFSs_impl<T> covering(AnnotationFS fs) {      // AI
    boundsUse = BoundsUse.covering;
    this.boundingFs = fs;
    return this;
  }

  @Override
  public SelectFSs_impl<T> covering(int begin, int end) {      // AI
    boundsUse = BoundsUse.covering;
    this.boundingFs = makePosAnnot(begin, end);
    return this;
  }

  @Override
  public SelectFSs_impl<T> between(AnnotationFS fs1, AnnotationFS fs2) {   // AI
    final boolean reverse = fs1.getEnd() > fs2.getBegin(); 
    int begin = (reverse ? fs2 : fs1).getEnd();
    int end   = (reverse ? fs1 : fs2).getBegin();
    
    if (begin > end) {
      isEmptyBoundingFs = true;
    } else {
      this.boundingFs = makePosAnnot(begin, end);
    }
    this.boundsUse = BoundsUse.coveredBy;
//    this.isIncludeAnnotWithEndBeyondBounds = true; // default    
    return this;
  }
  
  /* (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#at(org.apache.uima.jcas.tcas.Annotation)
   */
  @Override
  public SelectFSs<T> at(AnnotationFS fs) {
    boundsUse = BoundsUse.sameBeginEnd;
    boundingFs = fs;
    return this;
  }

  /* (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#at(int, int)
   */
  @Override
  public SelectFSs<T> at(int begin, int end) {
    return at(makePosAnnot(begin, end));
  }

  private String maybeMsgPosition() {
    StringBuilder sb = new StringBuilder();
    if (startingFs != null) {
      if (startingFs instanceof Annotation) {
        Annotation a = (Annotation)startingFs;
        sb.append(" at position begin: ").append(a.getBegin()).append(", end: ")
          .append(a.getEnd());
      } else {
        sb.append(" at moveTo position given by Feature Structure:\n");
        startingFs.prettyPrint(2, 2, sb, false);
        sb.append("\n ");
      }
    }
    if (shift != 0) {
      sb.append(" shifted by: ").append(shift);
    }
    return sb.toString();
  }
  
  private void prepareTerminalOp() {
    if (boundsUse == null) {
      boundsUse = BoundsUse.notBounded;
    } 
    
    maybeValidateAltSource();
    
    final boolean isUseAnnotationIndex = 
        ((index != null) && (index instanceof AnnotationIndex)) ||
        isNonOverlapping ||
//        isPositionUsesType ||  REMOVED see https://issues.apache.org/jira/browse/UIMA-5536
        isTypePriority ||
        isIncludeAnnotBeyondBounds || 
        boundsUse != BoundsUse.notBounded ||
        isFollowing || isPreceding;
    
    if (isUseAnnotationIndex) {
      forceAnnotationIndex();  // throws if non-null index not an annotation index
    }
    // REMOVED see https://issues.apache.org/jira/browse/UIMA-5536
//    if (isTypePriority) {
//      isPositionUsesType = true;
//    }
        
    if (ti == null) {
      if (index != null) {
        ti = (TypeImpl) index.getType();
      }
    } else {
      // type is specified
      if (index != null) {
        if (((TypeImpl)index.getType()).subsumes(ti)) {
          index = ((LowLevelIndex)index).getSubIndex(ti);
        }
      } else {
        if (ti.isAnnotationType()) {
          forceAnnotationIndex();  // when index is null, but ti is not null and is annotation
        }
      }
    }
    
    if (isUseAnnotationIndex && null == ti) {
      ti = (TypeImpl) view.getAnnotationType();
    } 
    
    if (ti == null) {
      ti = view.getTypeSystemImpl().getTopType();
    }
    
    if (boundsUse == BoundsUse.covering) {
      isIncludeAnnotBeyondBounds = true;  
    }
    
    // force ordering 
    boolean orderingNeeded = 
        !isUnordered ||
        shift != 0 ||
        boundsUse != BoundsUse.notBounded ||
        isFollowing || isPreceding;
    
    isUnordered = ! orderingNeeded;
    
    
  }
  
  private void maybeValidateAltSource() {
    if (!isAltSource) return;
    
    if (index != null ||
        boundsUse != BoundsUse.notBounded ||
        isAllViews || 
        isFollowing ||
        isPreceding ||
        startingFs != null) {
      /** Select with FSList or FSArray may not specify bounds, starting position, following, or preceding. */
      throw new CASRuntimeException(CASRuntimeException.SELECT_ALT_SRC_INVALID);
    }
  }
  
  private void incr(FSIterator<T> it) {
    it.moveToNext();    
  }
  
  /*********************************
   * terminal operations
   * returning other than SelectFSs
   * 
   * Hierarchy of interpretation of setup:
   *   - index
   *   - type
   * 
   *   - allViews: ignored: things only with annotation index
   *               order among views is arbitrary, each view done together
   *               base view skipped
   *               
   *********************************/
  
  /**
   * F S I t e r a t o r
   * -------------------
   */
  @Override
  public FSIterator<T> fsIterator() {
    if (isFollowing && isBackwards) {
      isBackwards = false;
      return make_or_copy_snapshot(fsIterator1(), true);
//      LowLevelIterator<T> baseIterator = fsIterator1();
//      FSIterator<T> it;
//      if (baseIterator instanceof FsIterator_subtypes_snapshot) {
//        it = new FsIterator_backwards<>(baseIterator.copy()); // avoid making another array
//      } else {
//        T[] a = (T[]) asArray(baseIterator);
//        it = new FsIterator_backwards<>(
//                             new FsIterator_subtypes_snapshot<T>(
//                                 a, 
//                                 (LowLevelIndex<T>) index, 
//                                 IS_ORDERED,
//                                 baseIterator.getComparator()));
//      }
//      return (limit == -1)
//          ? it
//            // rewrap with limit - needs to be outer shell to get right invalid behavior
//          : new FsIterator_limited<>(it, limit);          
    }
    
    if (isPreceding) {
      boolean bkwd = isBackwards;   // save isBackwards flag.  
                                    
      isBackwards = true;   // because need the iterator to move from the position to the front.
      return make_or_copy_snapshot(fsIterator1(), bkwd);  // this iterator fails to skip annotations whose end is > positioning begin
//      LowLevelIterator<T> baseIterator = fsIterator1();  // this iterator fails to skip annotations whose end is > positioning begin
//      T[] a = (T[]) asArray(baseIterator);
//      FSIterator<T> it = new FsIterator_subtypes_snapshot<T>(
//                               a,
//                               (LowLevelIndex<T>) index,
//                               IS_ORDERED,
//                               baseIterator.getComparator());
//      if (!bkwd) {
//        it = new FsIterator_backwards<>(it); // because array is backwards
//      }
//      return (limit == -1) 
//          ? it
//            // rewrap with limit - needs to be outer shell to get right invalid behavior
//          : new FsIterator_limited<>(it, limit); 
    }
    
    // all others, including isFollowing but not backwards
    return fsIterator1();
  }
  
  private FSIterator<T> make_or_copy_snapshot(LowLevelIterator<T> baseIterator, boolean bkwd) {
    FSIterator<T> it;
    T[] a = (T[]) asArray(baseIterator, FeatureStructure.class);  // array is in forward order because 
                                          // it's produced by a backwards iterator, but then the array is reversed
    it = new FsIterator_subtypes_snapshot<>(
        a,
        (LowLevelIndex<T>) index,
        IS_ORDERED,
        baseIterator.getComparator());
    
    if (!bkwd) {
      it = new FsIterator_backwards<>(it);
    }

    return (limit == -1)
        ? it
          // rewrap with limit - needs to be outer shell to get right invalid behavior
        : new FsIterator_limited<>(it, limit);
  }
  
  private LowLevelIterator<T> fsIterator1() {
    prepareTerminalOp();
    LowLevelIterator<T> it = isAllViews 
                      ? //new FsIterator_aggregation_common<T>(getPlainIteratorsForAllViews(), )
                        createFsIterator_for_all_views()
                      : plainFsIterator(index, view);

    it = maybeWrapBackwards(it);                  
    maybePosition(it);  // position needs to come after backwards because that sets the position
    maybeShift(it);     // shift semantically needs to come after backwards
    return (limit == -1) ? it : new FsIterator_limited<>(it, limit);    
  }
  
  /**
   * for a selected index, return an iterator over all the views for that index
   * @return iterator over all views for that index, unordered
   */
  private FsIterator_aggregation_common<T> createFsIterator_for_all_views() {
    final int nbrViews = view.getNumberOfViews();
    LowLevelIterator<T>[] ita = new LowLevelIterator[nbrViews];
//    LowLevelIndex<T>[] indexes = new LowLevelIndex[nbrViews];
    
    for (int i = 1; i <= nbrViews; i++) {
      CASImpl v = (i == 1) ? view.getInitialView() : (CASImpl) view.getView(i);
      LowLevelIndex<T> index_local = (LowLevelIndex<T>) getIndexForView(v);
      ita[i - 1] = plainFsIterator(index_local, v);
//      indexes[i - 1] = index;
    }
//    return new FsIterator_aggregation_common<T>(ita, new FsIndex_aggr<>(indexes));
    return new FsIterator_aggregation_common<>(ita, null, null);

  }
  
//  private LowLevelIterator<T>[] getPlainIteratorsForAllViews() {
//    final int nbrViews = view.getNumberOfViews();
//    LowLevelIterator<T>[] ita = new LowLevelIterator[nbrViews];
//    
//    for (int i = 1; i <= nbrViews; i++) {
//      CASImpl v = (i == 1) ? view.getInitialView() : (CASImpl) view.getView(i);
//      ita[i - 1] = plainFsIterator(getIndexForView(v), v);
//    }
//    return ita;
//  }
  
  /** 
   * gets the index for a view that corresponds to the specified index
   *   by matching the index specs and type code
   * @param v -
   * @return -
   */
  private LowLevelIndex<T> getIndexForView(CASImpl v) {
    if (index == null) {
      return null;
    }
    
    FSIndexRepositoryImpl ir = (FSIndexRepositoryImpl) v.getIndexRepository();
    if (index instanceof FsIndex_iicp) {
      FsIndex_iicp idx = (FsIndex_iicp) index;
      return ir.getIndexBySpec(idx.getTypeCode(), idx.getIndexingStrategy(), idx.getComparatorImplForIndexSpecs()); 
    }
    FsIndex_singletype idx = (FsIndex_singletype) index;
    return ir.getIndexBySpec(idx.getTypeCode(), idx.getIndexingStrategy(), idx.getComparatorImplForIndexSpecs());
  }
  
  /**
   * 
   * @param idx the index selected, corresponds to a type + its subtypes, 
   *        or if null, either an alternate source or means all types
   * @param v the cas
   * @return an iterator
   */
  private LowLevelIterator<T> plainFsIterator(LowLevelIndex<T> idx, CASImpl v) {
    if (null == idx) { 
      // no bounds, not ordered
      // type could be null
      // could be alternate source
      
      if (isAltSource) {
        return altSourceIterator();
      } else {
        // idx is null after prepareTerminalOp has been called.
        // guaranteed not annotationindex or any op that requires that
        return v.indexRepository.getAllIndexedFS(ti);
      }
    }
    
    final boolean isSortedIndex = idx.getIndexingStrategy() == FSIndex.SORTED_INDEX;
    final boolean isAnnotationIndex = idx instanceof AnnotationIndex;
    final FsIndex_annotation<Annotation> ai = isAnnotationIndex ? (FsIndex_annotation)idx: null;
    LowLevelIterator<T> it;
    if (boundsUse == BoundsUse.notBounded) {
      if (!isSortedIndex) {
        // set index or bag index
        it = (LowLevelIterator<T>) idx.iterator();       
      } else {
        // index is sorted but no bounds are being used.  Varieties:
        //   - AnnotationIndex:
        //     - overlapping / non-overlapping  (ambiguous, unambiguous)
        //   - any sorted index including AnnotationIndex:
        //     - typePriority / ignore typePriority
        //     - orderNotNecessary / orderNeeded
        //   - preceding: need to skip over annotations whose end is > positioning-begin

        it = isAnnotationIndex 
               ? (LowLevelIterator<T>) ai.iterator( ! isNonOverlapping, IS_NOT_STRICT, isUnordered, ! isTypePriority)
               : idx.iterator(isUnordered, ! isTypePriority);
        if (isPreceding) {
          // filter the iterator to skip annotations whose end is > the position-begin
          it = new FilteredIterator<>(it, fs ->
              // true if ok, false to skip
              ((Annotation) fs).getEnd() <= ((Annotation) startingFs).getBegin());
        }
      }
    } else {
      if (isEmptyBoundingFs) {
        return (LowLevelIterator<T>) LowLevelIterator.FS_ITERATOR_LOW_LEVEL_EMPTY;
      }
      // bounds in use, index must be annotation index, is ordered
      it = (LowLevelIterator<T>) new Subiterator<>(
          (FSIterator<Annotation>) idx.iterator(isUnordered, !isTypePriority),
          boundingFs,
          !isNonOverlapping,  // ambiguous
          !isIncludeAnnotBeyondBounds,  // strict 
          boundsUse,
          isTypePriority,
          isSkipSameBeginEndType);
    }

    return it;
  }
  
  private LowLevelIterator<T> maybeWrapBackwards(LowLevelIterator<T> it) {
    if (isBackwards) {
      it = new FsIterator_backwards<>(it);  // positions the underlying iterator to last,
                                            // which is first for going backwards
    }
    return it;
  }
  
    
  private LowLevelIterator<T> altSourceIterator() {
    T[] filtered;
    if (sourceFSList != null) {
      List<T> filteredItems = new ArrayList<>();
      FSList fsl = sourceFSList;
      while (!(fsl instanceof EmptyFSList)) {
        NonEmptyFSList nefsl = (NonEmptyFSList) fsl;
        T item = (T) nefsl.getHead();
        if ((isNullOK || null != item) &&
            ti.subsumes((TypeImpl)item.getType())) {
          filteredItems.add(item);
        }
        fsl = nefsl.getTail();
      }
      filtered = filteredItems.toArray((T[]) Array.newInstance(FeatureStructure.class, filteredItems.size()));          
    } else {
      
      // skip filtering if nullOK and no subsumption test needed because type = TOP or higher
      boolean noTypeFilter = ti == view.getTypeSystemImpl().topType;
      if (!isNullOK && noTypeFilter) {
        return new FsIterator_subtypes_snapshot<>((T[]) sourceFSArray, null, IS_UNORDERED, null);
      }
      
      List<T> filteredItems = new ArrayList<>();
      boolean noNullsWereFiltered = true;
      for (FeatureStructure item : sourceFSArray) {
        if (!isNullOK && null == item) {
          noNullsWereFiltered = false;
          continue;  // null items may be skipped
        }
        
        if (noTypeFilter || ti.subsumes((TypeImpl)item.getType())) {
          filteredItems.add((T)item);
        }
      }
      
      if (noTypeFilter && !noNullsWereFiltered) {
        return new FsIterator_subtypes_snapshot<>(
            (T[]) sourceFSArray,
            null,
            IS_UNORDERED,
            null);         
      }
      
      filtered = filteredItems.toArray((T[]) Array.newInstance(FeatureStructure.class, filteredItems.size()));                    
    }        
    return new FsIterator_subtypes_snapshot<>(
        filtered,
        null,
        IS_UNORDERED,
        null);  // items not sorted 
  }
  
  @Override
  public Iterator<T> iterator() {
    return fsIterator();
  }

  /*
   * (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#asList()
   * 
   * The operation of this is to make an iterator which is directly addressable,
   * and then return an instance of AbstractList<N>
   * 
   */
  @Override
  public ArrayList<T> asList() {
    return asArrayList((LowLevelIterator<T>) fsIterator());
  }
  
  private ArrayList<T> asArrayList(LowLevelIterator<T> it) {
    ArrayList<T> al = new ArrayList<>();
    it.getArrayList(al);
    return al;
  }
  
  /* (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#asArray()
   */
  @Override
  public T[] asArray(Class<? super T> clazz) {
    return asArray((LowLevelIterator<T>)fsIterator(), clazz);
  }
  
  /**
   * This is a terminal operation, so can use/modify the original iterator
   * @param it the iterator positioned at the start position
   * @param clazz the class of the result
   * @return an array of elements from the position to the end
   */
  private T[] asArray(LowLevelIterator<T> it, Class<? super T> clazz) {
    // can't use the iterator's getArray method, because that returns the entire 
    // array, starting from the first position thru the last position,
    // and the iterator might have been positioned other than the starting spot
    // by a following or startAt etc.
    
    ArrayList<T> a = asArrayList(it);
    T[] r = (T[]) Array.newInstance(clazz, a.size());
    return a.toArray(r);
  }
    
//  private T[] asArray(LowLevelIterator<T> it) {
//    return asArray(it, (Class<? super T>) ((TypeImpl)it.getType()).javaClass);
//  }
  

  private Annotation makePosAnnot(int begin, int end) {
    if (end < begin) {
      throw new IllegalArgumentException("End value must be >= Begin value");
    }
    return new Annotation(jcas, begin, end);
  }
  
  /**
   * Iterator respects backwards
   * 
   * Sets the characteristics from the context:
   *   IMMUTABLE / NONNULL / DISTINCT - always
   *   CONCURRENT - never
   *   ORDERED - unless orderNotNeeded index or not SORTED_INDEX or SET_INDEX
   *   SORTED - only for SORTED_INDEX (and not orderNotNeeded?)
   *   SIZED - if exact size is (easily) known, just from index.
   *           false if bounded, unambiguous
   *   SUBSIZED - if spliterator result from trysplit also is SIZED, set to true for now
   * 
   * trySplit impl: 
   *   always returns null (no parallelism support for now) 
   * @return the spliterator 
   */
  @Override
  public Spliterator<T> spliterator() {
    return new Spliterator<T>() {

      private final FSIterator<T> it = fsIterator();
      
      private final FSIndex<T> localIndex = index;
      
      private final Comparator<? super T> comparator = 
          (localIndex != null && localIndex.getIndexingStrategy() == FSIndex.SORTED_INDEX) 
            ? (Comparator<? super T>)localIndex 
            : null;
                                                          
      private final int characteristics;
      { // set the characteristics and comparator
        // always set
        int c = Spliterator.IMMUTABLE | Spliterator.NONNULL | Spliterator.DISTINCT;
        
        if (boundsUse == BoundsUse.notBounded && !isNonOverlapping) {
          c |= Spliterator.SIZED | Spliterator.SUBSIZED;
        }
        
        // set per indexing strategy
        switch ((null == localIndex) ? -1 : localIndex.getIndexingStrategy()) {
        case FSIndex.SORTED_INDEX: c |= Spliterator.ORDERED | Spliterator.SORTED; break;
        case FSIndex.SET_INDEX: c |= Spliterator.ORDERED; break;
        default: // do nothing
        }
        
        characteristics = c;        
      }
      
      @Override
      public boolean tryAdvance(Consumer<? super T> action) {
        if (it.isValid()) {
          action.accept(it.getNvc());
          incr(it);
          return true;
        }
        return false;
      }

      @Override
      public Spliterator<T> trySplit() {
        // return null for now
        // could implement something based on type of fsIterator.
        return null;
      }

      @Override
      public long estimateSize() {
        return ((characteristics & Spliterator.SIZED) == Spliterator.SIZED && localIndex != null) ? localIndex.size() : Long.MAX_VALUE;
      }

      @Override
      public int characteristics() {
        return characteristics;
      }

      @Override
      public Comparator<? super T> getComparator() {
        if (comparator != null) {
          return comparator;
        }
        if ((characteristics & Spliterator.SORTED) == Spliterator.SORTED) {
          return null;
        }
        throw new IllegalStateException();
      }
    };
  }
  
  /*
   * returns the item the select is pointing to, or null 
   * if nullOK(false) then throws on null
   * (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#get()
   */
  @Override
  public T get() {
    return getNullChk();
  }
  
  private T getNullChk() {
    FSIterator<T> it = fsIterator();
    if (it.isValid()) {
      return it.getNvc();
    }
    if (!isNullOK) {  // if not specified, isNullOK == false
      throw new CASRuntimeException(CASRuntimeException.SELECT_GET_NO_INSTANCES, ti.getName(), maybeMsgPosition());
    }
    return null;
    
  }

  /*
   * like get() but throws if more than one item
   * (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#single()
   */
  @Override
  public T single() {
    T v = singleOrNull();
    if (v == null && !isNullOK) {
      throw new CASRuntimeException(CASRuntimeException.SELECT_GET_NO_INSTANCES, ti.getName(), maybeMsgPosition());
    }
    return v;
  }
  
  /*
   * like get() but throws if more than 1 item, always OK to return null if none
   * (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#singleOrNull()
   */
  @Override
  public T singleOrNull() {
    FSIterator<T> it = fsIterator();
    if (it.isValid()) {
      T v = it.getNvc();
      if (shift >= 0) {
        it.moveToNext();
      } else {
        it.moveToPrevious();
      }
      if (it.isValid()) {
        throw new CASRuntimeException(CASRuntimeException.SELECT_GET_TOO_MANY_INSTANCES, ti.getName(), maybeMsgPosition());
      }
      return v;
    }
    return null;
  }
  
  
  
  @Override
  public T get(int offset) {
    this.shift = offset;
    return getNullChk();
  }

  @Override
  public T single(int offset) {
    this.shift = offset;
    return single();
  }

  @Override
  public T singleOrNull(int offset) {
    this.shift = offset;
    return singleOrNull();
  }

  @Override
  public T get(TOP fs) {
    startAt(fs);
    return getNullChk();
  }

  @Override
  public T single(TOP fs) {
    startAt(fs);
    return single();
  }

  @Override
  public T singleOrNull(TOP fs) {
    startAt(fs);
    return singleOrNull();
  }

  @Override
  public T get(TOP fs, int offset) {
    startAt(fs, offset);
    return getNullChk();
  }

  @Override
  public T single(TOP fs, int offset) {
    startAt(fs, offset);
    return single();
  }

  @Override
  public T singleOrNull(TOP fs, int offset) {
    startAt(fs, offset);
    return singleOrNull();
  }

  @Override
  public T get(int begin, int end) {
    startAt(begin, end);
    return getNullChk();
  }

  @Override
  public T single(int begin, int end) {
    startAt(begin, end);
    return single();
  }

  @Override
  public T singleOrNull(int begin, int end) {
    startAt(begin, end);
    return singleOrNull();
  }

  @Override
  public T get(int begin, int end, int offset) {
    startAt(begin, end, offset);
    return getNullChk();
  }

  @Override
  public T single(int begin, int end, int offset) {
    startAt(begin, end, offset);
    return single();
  }

  @Override
  public T singleOrNull(int begin, int end, int offset) {
    startAt(begin, end, offset);
    return singleOrNull();
  }

  /**
   * works for AnnotationIndex or general index
   * 
   * position taken from startingFs (not necessarily an Annotation subtype)
   *   - goes to left-most "equal" using comparator, or if none equal, to the first one &gt; startingFs
   *     -- using moveTo(fs)
   * 
   * special processing for AnnotationIndex (only):
   *   - typePriority - use or ignore
   *     -- ignored: after moveTo(fs), moveToPrevious while begin and end ==
   *       // REMOVED see https://issues.apache.org/jira/browse/UIMA-5536 --- and if isPositionUsesType types are == 
   * @param it iterator to position
   * @return it positioned if needed
   */
  private FSIterator<T> maybePosition(FSIterator<T> it) {
    if (!it.isValid() || startingFs == null || boundsUse != BoundsUse.notBounded) {
      return it;
    }
    
    it.moveTo(startingFs);
    
    // next commented out because the underlying iterator already does this
//    if (index != null && index instanceof AnnotationIndex && !isFollowing && !isPreceding) {
//      if (!isTypePriority) {
//        int begin = ((Annotation)startingFs).getBegin();
//        int end = ((Annotation)startingFs).getEnd();
//        Type type = startingFs.getType();
//        Annotation fs = (Annotation) it.get();
//        while (begin == fs.getBegin() && end == fs.getEnd() 
////               && (!isPositionUsesType || type == fs.getType())
//               ) {
//          it.moveToPreviousNvc();
//          if (!it.isValid()) {
//            it.moveToFirst();
//            return it;
//          }
//          fs = (Annotation) it.get();
//        }
//        it.moveToNext();
//      }
//    }
    
    if (isFollowing) {
      final int end = ((Annotation)startingFs).getEnd();
      while (it.isValid() && ((Annotation)it.get()).getBegin() < end) {
        it.moveToNext();
      }
    } else if (isPreceding) {
      final int begin = ((Annotation)startingFs).getBegin();
      while (it.isValid() && ((Annotation)it.get()).getEnd() > begin) {
        it.moveToPrevious();
      }
    }
    
    return it;
  }
  
  private FSIterator<T> maybeShift(FSIterator<T> it) {
    if (shift != 0) {
      int ps = Math.abs(shift);
      
      for (int i = 0; i < ps; i++) {
        if (shift < 0) {
          it.moveToPrevious();
        } else {
          it.moveToNext();
        }
      }
    }
    return it;
  }
  
  /********************************************
   * The methods below are alternatives 
   * to the methods above, that combine
   * frequently used patterns into more
   * concise forms using positional arguments
   ********************************************/

  /* (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#following(org.apache.uima.jcas.cas.TOP)
   */
  @Override
  public SelectFSs<T> following(Annotation fs) {
    return following(fs, 0);
  }

  /* (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#following(int, int)
   */
  @Override
  public SelectFSs<T> following(int position) {
    return following(position, 0);
  }

  /* (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#following(org.apache.uima.jcas.cas.TOP, int)
   */
  @Override
  public SelectFSs<T> following(Annotation fs, int offset) {
    if (fs.getBegin() < fs.getEnd()) {
      fs = makePosAnnot(fs.getEnd(), fs.getEnd());
    }
    return commonFollowing(fs, offset);
  }

  /* (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#following(int, int, int)
   */
  @Override
  public SelectFSs<T> following(int position, int offset) {
    return commonFollowing(makePosAnnot(position, position), offset);
  }

  /* (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#preceding(org.apache.uima.jcas.cas.TOP)
   */
  @Override
  public SelectFSs<T> preceding(Annotation fs) {
    return preceding(fs, 0);
  }

  /* (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#preceding(int, int)
   */
  @Override
  public SelectFSs<T> preceding(int position) {
    return preceding(position, 0);
  }

  /* (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#preceding(org.apache.uima.jcas.cas.TOP, int)
   */
  @Override
  public SelectFSs<T> preceding(Annotation annotation, int offset) {
    if (annotation.getEnd() < Integer.MAX_VALUE) {
      annotation = makePosAnnot(annotation.getBegin(), Integer.MAX_VALUE);
    }
    return commonPreceding(annotation, offset);
  }

  /* (non-Javadoc)
   * @see org.apache.uima.cas.SelectFSs#preceding(int, int, int)
   */
  @Override
  public SelectFSs<T> preceding(int position, int offset) {
    return commonPreceding(makePosAnnot(position, Integer.MAX_VALUE), offset);
  }
  
  /************************
   * NOT USED
   */
//  public SelectFSs_impl<T> sameBeginEnd() {  // AI
//    boundsUse = BoundsUse.sameBeginEnd;
//    return this;
//  }

  /**
   * validations
   *   isAnnotationIndex => startingFs is Annotation 
   *   isAllViews:  doesn't support startAt, coveredBy and friends, backwards
   *   isAllViews:  supports limit, shift
   */
    
//  private void validateSinglePosition(TOP fs, int offset) {
//    if (startingFs != null) {
//      /* Select - multiple starting positions not allowed */
//      throw CASRuntimeException(CASRuntimeException.);
//    }
//    startingFs = fs;
//    
//    if (offset != 0) { 
//      if (shift != 0) {
//        /* Select - multiple offset shifting not allowed */
//        throw  CASRuntimeException(CASRuntimeException.);
//      }
//      shift = offset;
//    }
//  }
  
  private SelectFSs<T> commonFollowing(Annotation annotation, int offset) {
    this.startingFs = annotation;
    this.shift = offset;
    isFollowing = true;
    return this;
  }

  private SelectFSs<T> commonPreceding(Annotation annotation, int offset) {
//    validateSinglePosition(fs, offset);
    this.startingFs = annotation;
    this.shift = offset;
    isPreceding = true;
    return this;
  }
  
  private void forceAnnotationIndex() {
    if (index == null) {
      index = (LowLevelIndex<T>) (  (ti == null) ? 
                                       view.getAnnotationIndex() :
                                       view.getAnnotationIndex(ti));
    } else {
      if (!(index instanceof AnnotationIndex)) {
        /** Index "{0}" must be an AnnotationIndex. */ 
        throw new CASRuntimeException(CASRuntimeException.ANNOTATION_INDEX_REQUIRED, index);
      }
    }
  }
  
  private Stream<T> stream() {
    return StreamSupport.stream(spliterator(), false); // false = default not parallel
  }

  
  /* ***************************************
   *   S T R E A M   methods
   * these convert the result to a stream and apply the method
   */
  @Override
  public Stream<T> filter(Predicate<? super T> predicate) {
    return stream().filter(predicate);
  }

  @Override
  public <R> Stream<R> map(Function<? super T, ? extends R> mapper) {
    return stream().map(mapper);
  }

  @Override
  public IntStream mapToInt(ToIntFunction<? super T> mapper) {
    return stream().mapToInt(mapper);
  }

  @Override
  public LongStream mapToLong(ToLongFunction<? super T> mapper) {
    return stream().mapToLong(mapper);
  }

  @Override
  public DoubleStream mapToDouble(ToDoubleFunction<? super T> mapper) {
    return stream().mapToDouble(mapper);
  }

  @Override
  public <R> Stream<R> flatMap(Function<? super T, ? extends Stream<? extends R>> mapper) {
    return stream().flatMap(mapper);
  }

  @Override
  public IntStream flatMapToInt(Function<? super T, ? extends IntStream> mapper) {
    return stream().flatMapToInt(mapper);
  }

  @Override
  public LongStream flatMapToLong(Function<? super T, ? extends LongStream> mapper) {
    return stream().flatMapToLong(mapper);
  }

  @Override
  public DoubleStream flatMapToDouble(Function<? super T, ? extends DoubleStream> mapper) {
    return stream().flatMapToDouble(mapper);
  }

  @Override
  public Stream<T> distinct() {
    return stream().distinct();
  }

  @Override
  public Stream<T> sorted() {
    return stream().sorted();
  }

  @Override
  public Stream<T> sorted(Comparator<? super T> comparator) {
    return stream().sorted(comparator);
  }

  @Override
  public Stream<T> peek(Consumer<? super T> action) {
    return stream().peek(action);
  }

  @Override
  public Stream<T> limit(long maxSize) {
    return stream().limit(maxSize);
  }

  @Override
  public Stream<T> skip(long n) {
    return stream().skip(n);
  }

  @Override
  public void forEach(Consumer<? super T> action) {
    stream().forEach(action);
  }

  @Override
  public void forEachOrdered(Consumer<? super T> action) {
    stream().forEachOrdered(action);
  }

  @Override
  public Object[] toArray() {
    return stream().toArray();
  }

  @Override
  public <A> A[] toArray(IntFunction<A[]> generator) {
    return stream().toArray(generator);
  }

  @Override
  public T reduce(T identity, BinaryOperator<T> accumulator) {
    return stream().reduce(identity, accumulator);
  }

  @Override
  public Optional<T> reduce(BinaryOperator<T> accumulator) {
    return stream().reduce(accumulator);
  }

  @Override
  public <U> U reduce(U identity, BiFunction<U, ? super T, U> accumulator,
      BinaryOperator<U> combiner) {
    return stream().reduce(identity, accumulator, combiner);
  }

  @Override
  public <R> R collect(Supplier<R> supplier, BiConsumer<R, ? super T> accumulator,
      BiConsumer<R, R> combiner) {
    return stream().collect(supplier, accumulator, combiner);
  }

  @Override
  public <R, A> R collect(Collector<? super T, A, R> collector) {
    return stream().collect(collector);
  }

  @Override
  public Optional<T> min(Comparator<? super T> comparator) {
    return stream().min(comparator);
  }

  @Override
  public Optional<T> max(Comparator<? super T> comparator) {
    return stream().max(comparator);
  }

  @Override
  public long count() {
    return stream().count();
  }

  @Override
  public boolean anyMatch(Predicate<? super T> predicate) {
    return stream().anyMatch(predicate);
  }

  @Override
  public boolean allMatch(Predicate<? super T> predicate) {
    return stream().allMatch(predicate);
  }

  @Override
  public boolean noneMatch(Predicate<? super T> predicate) {
    return stream().noneMatch(predicate);
  }

  @Override
  public Optional<T> findFirst() {
    return stream().findFirst();
  }

  @Override
  public Optional<T> findAny() {
    return stream().findAny();
  }

  @Override
  public boolean isParallel() {
    return stream().isParallel();
  }

  @Override
  public Stream<T> sequential() {
    return stream().sequential();
  }

  @Override
  public Stream<T> parallel() {
    return stream().parallel();
  }

  @Override
  public Stream<T> onClose(Runnable closeHandler) {
    return stream().onClose(closeHandler);
  }

  @Override
  public void close() {
    stream().close();
  }

  @Override
  public Stream<T> unordered() {
    return stream().unordered();
  }

  @Override
  public boolean isEmpty() {
    if (this.limit == 0) return true;
    return fsIterator().size() == 0;
  }
}
