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

import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.apache.uima.cas.CAS;
import org.apache.uima.cas.CASRuntimeException;
import org.apache.uima.cas.FSIndex;
import org.apache.uima.cas.FeatureStructure;
import org.apache.uima.cas.Marker;
import org.apache.uima.cas.impl.XmiSerializationSharedData.OotsElementData;
import org.apache.uima.internal.util.IntBitSet;
import org.apache.uima.internal.util.IntHashSet;
import org.apache.uima.internal.util.IntStack;
import org.apache.uima.internal.util.IntVector;
import org.apache.uima.internal.util.PositiveIntSet;
import org.apache.uima.internal.util.XmlElementName;
import org.apache.uima.util.Logger;
import org.xml.sax.ContentHandler;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;

/**
 * CAS serializer support for XMI and JSON formats.
 * 
 * There are multiple use cases.
 *   1) normal - the consumer is independent of UIMA
 *        - (maybe) support for delta serialization
 *   2) service calls:  
 *        - support deserialization with out-of-type-system set-aside, and subsequent serialization with re-merging
 *        - guarantee of using same xmi:id's as were deserialized when serializing
 *        - support for delta serialization
 * 
 * There is an outer class (one instance per "configuration" - reusable after configuration, and
 * an inner class - one per serialize call.
 * 
 * These classes are the common parts of serialization between XMI and JSON, mainly having to do with
 *   1) enquuing the FS to be serialized
 *   2) serializing according to their types and features
 *     
 * 
 * Most methods are package private, and not for public use
 *   
 *   XmiCasSerializer                              JsonCasSerializer
 *       Instance                                      Instance
 *      css ref ------->   CasSerializerSupport   <------ css ref
 *          
 *               
 *   XmiDocSerializer                                JsonDocSerializer            
 *       Instance                                       Instance 
 * (1 per serialize action)                         (1 per serialize action)
 *       cds ref ------->     CasDocSerializer  <-------   cds ref
 *                           csss points back
 *                      
 *                      
 * Construction:
 *   new Xmi/JsonCasSerializer 
 *      initializes css with new CasSerializerSupport
 *      
 *   serialize method creates a new Xmi/JsonDocSerializer inner class
 *      constructor creates a new CasDocSerializer,    
 *                      
 * Use Cases and Algorithms
 *   Support set-aside for out-of-type-system FS on deserialization (record in shareData)
 *     implies can't determine sharing status of things ref'd by features; need to depend on 
 *       multiple-refs-allowed flag.
 *       If multiple-refs found during serialization for feat marked non-shared, unshare these (make
 *         2 serializations, one or more inplace, for example.  
 *         Perhaps not considered an error.
 *     implies need (for non-delta case) to send all FSs that were deserialized - some may be ref'd by oots elements
 *       ** Could ** not do this if no oots elements, but could break some assumptions
 *       and this only would apply to non-delta - not worth doing
 *       
 *       
 *                      
 */

class CasSerializerSupport {
   
  // Special "type class" codes for list types. The LowLevelCAS.ll_getTypeClass() method
  // returns type classes for primitives and arrays, but not lists (which are just ordinary FS types
  // as far as the CAS is concerned). The serialization treats lists specially, however, and
  // so needs its own type codes for these.
  static final int TYPE_CLASS_INTLIST = 101;

  static final int TYPE_CLASS_FLOATLIST = 102;

  static final int TYPE_CLASS_STRINGLIST = 103;

  static final int TYPE_CLASS_FSLIST = 104;
    
  static int PP_LINE_LENGTH = 120;
  static int PP_ELEMENTS = 30;  // number of elements to do before nl
  
  final static Comparator<TypeImpl> COMPARATOR_SHORT_TYPENAME = new Comparator<TypeImpl>() {
    public int compare(TypeImpl object1, TypeImpl object2) {
      return object1.getShortName().compareTo(object2.getShortName());
    }
  };
   
  TypeSystemImpl filterTypeSystem;
  
  MarkerImpl marker;
  
  ErrorHandler eh = null;

  // UIMA logger, to which we may write warnings
  Logger logger;

  boolean isFormattedOutput;  // true for pretty printing
     
  /***********************************************
   *         C O N S T R U C T O R S             *  
   ***********************************************/

  /**
   * Creates a new CasSerializerSupport.
   * 
   */
  
  public CasSerializerSupport() {
  }

  
  
  /********************************************************
   *   Routines to set/reset configuration                *
   ********************************************************/
  /**
   * set or reset the pretty print flag (default is false)
   * @param pp true to do pretty printing of output
   * @return the original instance, possibly updated
   */
  public CasSerializerSupport setPrettyPrint(boolean pp) {
    this.isFormattedOutput = pp;
    return this;
  }
  
  /**
   * pass in a type system to use for filtering what gets serialized;
   * only those types and features which are defined this type system are included.
   * @param ts the filter
   * @return the original instance, possibly updated
   */
  public CasSerializerSupport setFilterTypes(TypeSystemImpl ts) {
    this.filterTypeSystem = ts;
    return this;
  }
  
  /**
   * set the Marker to specify delta cas serialization
   * @param m - the marker
   * @return the original instance, possibly updated
   */
  public CasSerializerSupport setDeltaCas(Marker m) {
    this.marker = (MarkerImpl) m;
    return this;
  }
  
  /**
   * set an error handler to receive information about errors
   * @param eh the error handler
   * @return the original instance, possibly updated
   */
  public CasSerializerSupport setErrorHandler(ErrorHandler eh) {
    this.eh = eh;
    return this;
  }
         
   /**

  }
  
  /***********************************************
   * Methods used to serialize items
   * Separate implementations for JSON and Xmi
   *
   ***********************************************/
  static abstract class CasSerializerSupportSerialize {
    
    abstract protected void initializeNamespaces();
        
    abstract protected void checkForNameCollision(XmlElementName xmlElementName);
        
    abstract protected void addNameSpace(XmlElementName xmlElementName);  

    abstract protected XmlElementName uimaTypeName2XmiElementName(String typeName);

    abstract protected void writeFeatureStructures(int elementCount) throws Exception;
    
    abstract protected void writeViews() throws Exception;
    
    abstract protected void writeView(int sofaAddr, int[] members) throws Exception;
    
    abstract protected void writeView(int sofaAddr, int[] added, int[] deleted, int[] reindexed) throws Exception;  
    
    abstract protected void writeFsStart(int addr, int typeCode) throws Exception;
    
    abstract protected void writeFs(int addr, int typeCode) throws Exception;
    
    abstract protected void writeListsAsIndividualFSs(int addr, int typeCode) throws Exception;
    
    abstract protected void writeArrays(int addr, int typeCode, int typeClass) throws Exception;
    
    abstract protected void writeEndOfIndividualFs() throws Exception;  
    
    abstract protected void writeEndOfSerialization() throws Exception;
  }
  
  /**
   * Use an inner class to hold the data for serializing a CAS. Each call to serialize() creates its
   * own instance.
   * 
   * package private to allow a test case to access
   * not static to share the logger and the initializing values (could be changed) 
   */
  class CasDocSerializer {

    // The CAS we're serializing.
    final  CASImpl cas;
    
    final TypeSystemImpl tsi;

    /** 
     * set of FSs that have been enqueued to be serialized
     *  Computed during "enqueue" phase, prior to encoding
     *  Used to prevent duplicate enqueuing
     */    
    final PositiveIntSet visited;  

    final XmiSerializationSharedData sharedData;
    
    // All FSs that are in an index somewhere.
    final IntVector indexedFSs;

    // The current queue for FSs to write out.
    private final IntStack queue;

    // utilities for dealing with CAS list types
    final ListUtils listUtils;
        
    XmlElementName[] typeCode2namespaceNames; // array, indexed by type code, giving XMI names for each type
    
    private final BitSet typeUsed;  // identifies types being serialized, a subset of all possible types
        
    boolean needNameSpaces = true; // may be false; currently for JSON only

    /**
     * map from a namespace expanded form to the namespace prefix, to identify potential collisions when
     *   generating a namespace string
     */
    final Map<String, String> nsUriToPrefixMap = new HashMap<String, String>();
           
    /**
     * the set of all namespace prefixes used, to disallow some if they are 
     *   in use already in set-aside data (xmi serialization) being merged back in
     */
    final Set<String> nsPrefixesUsed = new HashSet<String>();
    
    /**
     * Used to tell if a FS was created before or after mark.
     */
    final MarkerImpl marker;

    /**
     * Whether the serializer needs to check for filtered-out types/features. Set to true if type
     * system of CAS does not match type system that was passed to constructor of serializer.
     */
    final boolean isFiltering;

    /**
     * Whether the serializer needs to serialize only the deltas, that is, new FSs created after
     * mark represented by Marker object and preexisting FSs and Views that have been
     * modified. Set to true if Marker object is not null and CASImpl object of this serialize
     * matches the CASImpl in Marker object.
     */
    final boolean isDelta;
    
    private TypeImpl[] sortedUsedTypes;
    
    private final ErrorHandler eh;
    
    TypeSystemImpl filterTypeSystem;
    
    // map to reduce string usage by reusing equal string representations; lives just for one serialize call
    private final Map<String, String> uniqueStrings = new HashMap<String, String>();

    final boolean isFormattedOutput;
    
    private final CasSerializerSupportSerialize csss;

    /***********************************************
     *         C O N S T R U C T O R               *  
     ***********************************************/    
    CasDocSerializer(ContentHandler ch, CASImpl cas, XmiSerializationSharedData sharedData, CasSerializerSupportSerialize csss) {
      this.cas = cas;
      this.csss = csss;
      this.sharedData = sharedData;

      // copy outer class values into final inner ones, to keep the outer thread-safe
      filterTypeSystem = CasSerializerSupport.this.filterTypeSystem; 
      isFormattedOutput = CasSerializerSupport.this.isFormattedOutput; 
      marker = CasSerializerSupport.this.marker;
      eh = CasSerializerSupport.this.eh;

      tsi = cas.getTypeSystemImpl();
      visited = new PositiveIntSet();
      queue = new IntStack();
      indexedFSs = new IntVector();
      listUtils = new ListUtils(cas, logger, eh);
      typeUsed = new BitSet();

      isFiltering = filterTypeSystem != null && filterTypeSystem != tsi;
      if (marker != null && !marker.isValid()) {
  	    CASRuntimeException exception = new CASRuntimeException(
  	        CASRuntimeException.INVALID_MARKER, new String[] { "Invalid Marker." });
    	  throw exception;
      }
      isDelta = marker != null;
    }
        
//    // TODO: internationalize
//    private void reportWarning(String message) throws SAXException {
//      logger.log(Level.WARNING, message);
//      if (this.eh != null) {
//        this.eh.warning(new SAXParseException(message, null));
//      }
//    }

    /**
     * Starts serialization
     * @throws Exception 
     */
    void serialize() throws Exception {    
      typeCode2namespaceNames = new XmlElementName[tsi.getLargestTypeCode() + 1];
      
      csss.initializeNamespaces();
              
      int iElementCount = 1; // start at 1 to account for special NULL object

      enqueueIncoming(); //make sure we enqueue every FS that was deserialized into this CAS
      enqueueIndexed();
      enqueueNonsharedMultivaluedFS();
      enqueueFeaturesOfIndexed();
      iElementCount += indexedFSs.size();
      iElementCount += queue.size();

      FSIndex<FeatureStructure> sofaIndex = cas.getBaseCAS().indexRepository.getIndex(CAS.SOFA_INDEX_NAME);
      if (!isDelta) {
      	iElementCount += (sofaIndex.size()); // one View element per sofa
      	iElementCount += getElementCountForSharedData();
      } else {
        int numViews = cas.getBaseSofaCount();
        for (int sofaNum = 1; sofaNum <= numViews; sofaNum++) {
          FSIndexRepositoryImpl loopIR = (FSIndexRepositoryImpl) cas.getBaseCAS().getSofaIndexRepository(sofaNum);
          if (loopIR != null && loopIR.isModified()) {
            iElementCount++;
          }
        }
      }
      
      csss.writeFeatureStructures(iElementCount);
      
      csss.writeViews();
      
      csss.writeEndOfSerialization();
    }

    void writeViewsCommons() throws Exception {
      // Get indexes for each SofaFS in the CAS
      int numViews = cas.getBaseSofaCount();
      FeatureStructureImpl sofa = null;
      int sofaAddr = 0;
      
      for (int sofaNum = 1; sofaNum <= numViews; sofaNum++) {
        FSIndexRepositoryImpl loopIR = (FSIndexRepositoryImpl) cas.getBaseCAS().getSofaIndexRepository(sofaNum);
        if (sofaNum != 1 || cas.isInitialSofaCreated()) { //skip if initial view && no Sofa yet
                                                          // all non-initial-views must have a sofa
          sofa = (FeatureStructureImpl) cas.getView(sofaNum).getSofa();
          sofaAddr = sofa.getAddress();
        }
        if (loopIR != null) {
          if (!isDelta) {
            int[] fsarray = loopIR.getIndexedFSs();
            csss.writeView(sofaAddr, fsarray);
          } else { // is Delta Cas
        	  if (sofaNum != 1 && this.marker.isNew(sofa.getAddress())) {
        	    // for views created after mark (initial view never is - it is always created with the CAS)
        	    // write out the view as new
        	    int[] fsarray = loopIR.getIndexedFSs();
              csss.writeView(sofaAddr, fsarray);
        	  } else if (loopIR.isModified()) {
        	    csss.writeView(sofaAddr, loopIR.getAddedFSs(), loopIR.getDeletedFSs(), loopIR.getReindexedFSs());
          	}
          } 
        }
      }
    }                 
    
    // sort is by shortname of type
    TypeImpl[] getSortedUsedTypes() {
      if (null == sortedUsedTypes) {
        sortedUsedTypes = new TypeImpl[typeUsed.cardinality()];
        int i = 0;
        for (TypeImpl ti : getUsedTypesIterable()) {
          sortedUsedTypes[i++] = ti;
        }
        Arrays.sort(sortedUsedTypes, COMPARATOR_SHORT_TYPENAME);     
      }
      return sortedUsedTypes;
    }
    
    private Iterable<TypeImpl> getUsedTypesIterable() {
      return new Iterable<TypeImpl>() {
        public Iterator<TypeImpl> iterator() {
          return new Iterator<TypeImpl>() {
            private int i = 0;
            
            public boolean hasNext() {
              return typeUsed.nextSetBit(i) >= 0;
            }

            public TypeImpl next() {
              final int next_i = typeUsed.nextSetBit(i);
              if (next_i < 0) {
                throw new NoSuchElementException();
              }
              i = next_i + 1;
              return (TypeImpl) tsi.ll_getTypeForCode(next_i);
            }

            public void remove() {
              throw new UnsupportedOperationException();
            } 
          };
        }
      };
    }
     
//    private StringPair[] getSortedPrefixUri() {
//      StringPair[] r = new StringPair[nsUriToPrefixMap.size()];
//      int i = 0;
//      for (Map.Entry<String,String> e : nsUriToPrefixMap.entrySet()) {
//        r[i++] = new StringPair(e.getValue(), e.getKey());
//      }
//      Arrays.sort(r);
//      return r;
//    }
    
    /**
     * Enqueues all FS that are stored in the sharedData's id map.
     * This map is populated during the previous deserialization.  This method
     * is used to make sure that all incoming FS are echoed in the next
     * serialization.  It is required if there are out-of-type FSs that 
     * are being merged back into the serialized form; those might
     * reference some of these.
     */
    protected void enqueueIncoming() {
      if (sharedData == null)
        return;
      int[] fsAddrs = this.sharedData.getAllFsAddressesInIdMap();
      for (int addr : fsAddrs) {
        // don't enqueue id 0 - this is the "null" fs, which is automatically serialized by xmi
        if (addr == 0 || 
            (isDelta && !marker.isModified(addr))) {
          continue;
        }
        
        // is the first instance, but skip if delta and not modified or above the line or filtered
        int typeCode = enqueueCommon(addr);
        if (typeCode == -1) {
          return;
        }
        indexedFSs.add(addr);
      }
    }

     
    /**
     * Push the indexed FSs onto the queue.
     */
    private void enqueueIndexed() {
      FSIndexRepositoryImpl ir = (FSIndexRepositoryImpl) cas.getBaseCAS().getBaseIndexRepository();
      int[] fsarray = ir.getIndexedFSs();
      for (int fs : fsarray) {
        enqueueIndexedFs(fs);
      }

      // FSIndex sofaIndex = cas.getBaseCAS().indexRepository.getIndex(CAS.SOFA_INDEX_NAME);
      // FSIterator iterator = sofaIndex.iterator();
      // // Get indexes for each SofaFS in the CAS
      // while (iterator.isValid())
      int numViews = cas.getBaseSofaCount();
      for (int sofaNum = 1; sofaNum <= numViews; sofaNum++) {
        // SofaFS sofa = (SofaFS) iterator.get();
        // int sofaNum = sofa.getSofaRef();
        // iterator.moveToNext();
        FSIndexRepositoryImpl loopIR = (FSIndexRepositoryImpl) cas.getBaseCAS()
                .getSofaIndexRepository(sofaNum);
        if (loopIR != null) {
          fsarray = loopIR.getIndexedFSs();
          for (int fs : fsarray) {
            enqueueIndexedFs(fs);
          }
        }
      }
    }
    
    /** 
     * When serializing Delta CAS,
     * enqueue encompassing FS of nonshared multivalued FS that have been modified.
     * The embedded nonshared-multivalued item could be a list or an array
     */
    protected void enqueueNonsharedMultivaluedFS() {
      if (sharedData == null || !isDelta)
          return;
      int[] fsAddrs = sharedData.getNonsharedMulitValuedFSs();
      for (int addr : fsAddrs) {
        if (marker.isModified(addr)) {
          int encompassingFs = sharedData.getEncompassingFS(addr);
          if (-1 != enqueueCommonWithoutDeltaAndFilteringCheck(encompassingFs)) {  // only to set type used info and check if already enqueued
            indexedFSs.add(encompassingFs);
          }
        }   
      }
    }



    /**
     * Enqueue everything reachable from features of indexed FSs.
     */
    private void enqueueFeaturesOfIndexed() throws SAXException {
      final int max = indexedFSs.size();
      for (int i = 0; i < max; i++) {
        int addr = indexedFSs.get(i);
        int heapVal = cas.getHeapValue(addr);
        enqueueFeatures(addr, heapVal);
      }
    }

    int enqueueCommon(int addr) {
      return enqueueCommon(addr, true);
    }
    
    int enqueueCommonWithoutDeltaAndFilteringCheck(int addr) {
      return enqueueCommon(addr, false);
    }
    
    private int enqueueCommon(int addr, boolean doDeltaAndFilteringCheck) {

      final int typeCode = cas.getHeapValue(addr);     
      if (doDeltaAndFilteringCheck) {
        if (isDelta) {
          if (!marker.isNew(addr) && !marker.isModified(addr)) {
            return -1;
          }
        }
      
        if (isFiltering) {
          String typeName = tsi.ll_getTypeForCode(typeCode).getName();
          if (filterTypeSystem.getType(typeName) == null) {
            return -1; // this type is not in the target type system
          }
        }
      }
      
      // We set visited only if we're going to enqueue this.
      //   (In other words, please don't move this up in this method)
      //   This handles the use case:
      //   delta cas; element is not modified, but at some later point, we determine
      //   an embedded feature value (array or list) is modified, which requires we serialize out this
      //   fs as if it was modified.
     
      if (!visited.add(addr)) {
        return -1;
      }
      boolean alreadySet = typeUsed.get(typeCode);
      if (!alreadySet) {
        typeUsed.set(typeCode);

        String typeName = tsi.ll_getTypeForCode(typeCode).getName();
        XmlElementName newXel = csss.uimaTypeName2XmiElementName(typeName);

        if (!needNameSpaces) {
          csss.checkForNameCollision(newXel);
        }        
        typeCode2namespaceNames[typeCode] = newXel;
      }  
      return typeCode;
    }    
    /*
     * Enqueues an indexed FS. Does NOT enqueue features at this point.
     * Doesn't enqueue non-modified FS when delta
     * 
     * Sets visited to Ref-from-index or ref-multiple, if not skipping because of non-modified FS & delta
     */
    void enqueueIndexedFs(int addr) {
      if (enqueueCommon(addr) != -1) {
        indexedFSs.add(addr);
      }
    }

    /**
     * Enqueue an FS, and everything reachable from it.
     * 
     * @param addr
     *          The FS address.
     */
    private void enqueue(int addr) throws SAXException {    
      int typeCode = enqueueCommon(addr);
      if (typeCode == -1) {
        return;  
      }
      queue.push(addr);
      enqueueFeatures(addr, typeCode);
      // Also, for FSArrays enqueue the elements
      if (cas.isFSArrayType(typeCode)) { //TODO: won't get parameterized arrays??
        enqueueFSArrayElements(addr);
      }
    }
    
    
    boolean isArrayOrList(int typeCode) {
      return
          isArrayType(typeCode) ||
          isListType(typeCode);
    }
    
    private boolean isArrayType(int typeCode) {
      return
          (typeCode == tsi.intArrayTypeCode) ||
          (typeCode == tsi.floatArrayTypeCode) ||
          (typeCode == tsi.stringArrayTypeCode) ||
          (typeCode == tsi.fsArrayTypeCode) ||
          (typeCode == tsi.booleanArrayTypeCode) ||
          (typeCode == tsi.byteArrayTypeCode) ||
          (typeCode == tsi.shortArrayTypeCode) ||
          (typeCode == tsi.longArrayTypeCode) ||
          (typeCode == tsi.doubleArrayTypeCode);
    }
    
    private boolean isListType(int typeCode) {
      return
          listUtils.isIntListType(typeCode) ||
          listUtils.isFloatListType(typeCode) ||
          listUtils.isStringListType(typeCode) ||
          listUtils.isFsListType(typeCode);
    } 
    

    /**
     * Enqueue all FSs reachable from features of the given FS.
     * 
     * @param addr
     *          address of an FS
     * @param typeCode
     *          type of the FS
     * @param insideListNode
     *          true iff the enclosing FS (addr) is a list type
     */
    private void enqueueFeatures(int addr, int typeCode) throws SAXException {
      boolean insideListNode = listUtils.isListType(typeCode);
      int[] feats = tsi.ll_getAppropriateFeatures(typeCode);
      int featAddr, featVal, fsClass;
      for (int feat : feats) {
        if (isFiltering) {
          // skip features that aren't in the target type system
          String fullFeatName = tsi.ll_getFeatureForCode(feat).getName();
          if (filterTypeSystem.getFeatureByFullName(fullFeatName) == null) {
            continue;
          }
        }
        featAddr = addr + cas.getFeatureOffset(feat);
        featVal = cas.getHeapValue(featAddr);
        if (featVal == CASImpl.NULL) {      // 0 feature values are not serialized, these are defaulted upon deserialization
          continue;
        }

        // enqueue behavior depends on range type of feature
        fsClass = classifyType(tsi.range(feat));
        switch (fsClass) {
          case LowLevelCAS.TYPE_CLASS_FS: {
            enqueue(featVal);
            break;
          }
          case LowLevelCAS.TYPE_CLASS_INTARRAY:
          case LowLevelCAS.TYPE_CLASS_FLOATARRAY:
          case LowLevelCAS.TYPE_CLASS_STRINGARRAY:
          case LowLevelCAS.TYPE_CLASS_BOOLEANARRAY:
          case LowLevelCAS.TYPE_CLASS_BYTEARRAY:
          case LowLevelCAS.TYPE_CLASS_SHORTARRAY:
          case LowLevelCAS.TYPE_CLASS_LONGARRAY:
          case LowLevelCAS.TYPE_CLASS_DOUBLEARRAY:
          case LowLevelCAS.TYPE_CLASS_FSARRAY: {
            // we enqueue arrays as first-class objects if the feature has
            // multipleReferencesAllowed = true, or it has multiple refs, or it is indexed
            if (tsi.ll_getFeatureForCode(feat).isMultipleReferencesAllowed()) {
              enqueue(featVal);
            } else if (fsClass == LowLevelCAS.TYPE_CLASS_FSARRAY) {
              // but we do need to enqueue any FSs reachable from an FSArray
              enqueueFSArrayElements(featVal);
            }
            break;
          }
          case TYPE_CLASS_INTLIST:
          case TYPE_CLASS_FLOATLIST:
          case TYPE_CLASS_STRINGLIST:
          case TYPE_CLASS_FSLIST: {
            // we only enqueue lists as first-class objects if the feature has
            // multipleReferencesAllowed = true
            // or it has multiple refs, or it is indexed
            // OR if we're already inside a list node (this handles the tail feature correctly)
            if (tsi.ll_getFeatureForCode(feat).isMultipleReferencesAllowed() || insideListNode) {
              enqueue(featVal);
            } else if (fsClass == TYPE_CLASS_FSLIST) {
              // also, we need to enqueue any FSs reachable from an FSList
              enqueueFSListElements(featVal);
            }
            break;
          }
        }
      }  // end of loop over all features
    }

    /**
     * Enqueues all FS reachable from an FSArray.
     * 
     * @param addr
     *          Address of an FSArray
     */
    private void enqueueFSArrayElements(int addr) throws SAXException {
      final int size = cas.ll_getArraySize(addr);
      int pos = cas.getArrayStartAddress(addr);
      int val;
      for (int i = 0; i < size; i++) {
        val = cas.getHeapValue(pos);
        if (val != CASImpl.NULL) {
          enqueue(val);
        }
        ++pos;
      }
    }

    /**
     * Enqueues all FS reachable from an FSList. This does NOT include the list nodes themselves.
     * 
     * @param addr
     *          Address of an FSList
     */
    private void enqueueFSListElements(int addr) throws SAXException {
      int[] addrArray = listUtils.fsListToAddressArray(addr);
      for (int j = 0; j < addrArray.length; j++) {
        if (addrArray[j] != CASImpl.NULL) {
          enqueue(addrArray[j]);
        }
      }
    }

    /*
     * Encode the indexed FS in the queue.
     */
    void encodeIndexed() throws Exception {
      final int max = indexedFSs.size();
      for (int i = 0; i < max; i++) {
        encodeFS(indexedFSs.get(i));
      }
    }

    /*
     * Encode all other enqueued (non-indexed) FSs.
     * The queue is read out in FiFo order.
     * This insures that FsLists which are only 
     *   referenced via a single FS ref, get 
     *   encoded as [ x x x ] format rather than
     *   as individual FSs (because the individual
     *   items are also in the queue as items, but
     *   later).  The isWritten test prevents dupl writes
     */
    void encodeQueued() throws Exception {
      final int len = queue.size();
      for (int i = 0; i < len; i++) {
        encodeFS(queue.get(i));
      }
    }
    

    Integer[] collectAllFeatureStructures() {
      final int indexedSize = indexedFSs.size();
      final int qSize = queue.size();
      final int rLen = indexedSize + queue.size();
      Integer[] r = new Integer[rLen];
      int i = 0;
      for (; i < indexedSize; i++) {
        r[i] = indexedFSs.get(i);
      }
      for (int j = 0; j < qSize; j++) {
        r[i++] =  queue.get(j);
      }
      return r;
    }
    
    private int compareInts(int i1, int i2) {
      return (i1 == i2) ? 0 :
             (i1 >  i2) ? 1 : -1;
    }
    
    
    private int compareFeat(int o1, int o2, int featCode) {
      final int f1 = cas.ll_getIntValue(o1, tsi.annotSofaFeatCode);
      final int f2 = cas.ll_getIntValue(o2, tsi.annotSofaFeatCode);
      return compareInts(f1, f2);
    }
    
    final Comparator<Integer> sortFssByType = 
        new Comparator<Integer>() {
          public int compare(Integer o1, Integer o2) {
            final int typeCode1 = cas.getHeapValue(o1);
            final int typeCode2 = cas.getHeapValue(o2);
            int c = compareInts(typeCode1, typeCode2);
            if (c != 0) {
              return c;
            }
            final boolean hasSofa = tsi.subsumes(tsi.annotBaseTypeCode, typeCode1);
            if (hasSofa) {
              c = compareFeat(o1, o2, tsi.annotSofaFeatCode);
              if (c != 0) {
                return c;
              }
              final boolean isAnnot = tsi.subsumes(tsi.annotTypeCode, typeCode1);
              if (isAnnot) {
                c = compareFeat(o1, o2, tsi.startFeatCode);
                return (c != 0) ? c : compareFeat(o2, o1, tsi.endFeatCode);  // reverse order
              }
            }
            // not sofa nor annotation
            return compareInts(o1, o2);  // return in @id order
          }
      };
      
    /**
     * Encode an individual FS.
     * 
     * Json has 2 encodings   
     *  For type:
     *  "typeName" : [ { "@id" : 123,  feat : value .... },
     *                 { "@id" : 456,  feat : value .... },
     *                 ...
     *               ],
     *      ... 
     *        
     *  For id:
     *  "nnnn" : {"@type" : typeName ; feat : value ...}
     *     
     *  For cases where the top level type is an array or list, there is
     *  a generated feature name, "@collection" whose value is 
     *  the list or array of values associated with that type.
     *   
     * @param addr
     *          The address to be encoded.
     * @throws SAXException passthru
     */
    void encodeFS(int addr) throws Exception {

      final int typeCode = cas.getHeapValue(addr);

      final int typeClass = classifyType(typeCode);

      csss.writeFsStart(addr, typeCode);

      switch (typeClass) {
        case LowLevelCAS.TYPE_CLASS_FS: 
          csss.writeFs(addr, typeCode);
          break;
        
          
        case TYPE_CLASS_INTLIST:
        case TYPE_CLASS_FLOATLIST:
        case TYPE_CLASS_STRINGLIST:
        case TYPE_CLASS_FSLIST: 
          csss.writeListsAsIndividualFSs(addr, typeCode);
          break;
                
        case LowLevelCAS.TYPE_CLASS_FSARRAY:
        case LowLevelCAS.TYPE_CLASS_INTARRAY:
        case LowLevelCAS.TYPE_CLASS_FLOATARRAY:
        case LowLevelCAS.TYPE_CLASS_BOOLEANARRAY:
        case LowLevelCAS.TYPE_CLASS_BYTEARRAY:
        case LowLevelCAS.TYPE_CLASS_SHORTARRAY:
        case LowLevelCAS.TYPE_CLASS_LONGARRAY:
        case LowLevelCAS.TYPE_CLASS_DOUBLEARRAY:
        case LowLevelCAS.TYPE_CLASS_STRINGARRAY:
          csss.writeArrays(addr, typeCode, typeClass);
          break;
        
        default: 
          throw new RuntimeException("Error classifying FS type.");
      }
      
      csss.writeEndOfIndividualFs();
    }
    
    int filterType(int addr) {
      if (isFiltering) {
        String typeName = tsi.ll_getTypeForCode(cas.getHeapValue(addr)).getName();
        if (filterTypeSystem.getType(typeName) == null) {
          return 0;
        }
      }
      return addr;
    }
    
        
    /**
     * Classifies a type. This returns an integer code identifying the type as one of the primitive
     * types, one of the array types, one of the list types, or a generic FS type (anything else).
     * <p>
     * The {@link LowLevelCAS#ll_getTypeClass(int)} method classifies primitives and array types,
     * but does not have a special classification for list types, which we need for XMI
     * serialization. Therefore, in addition to the type codes defined on {@link LowLevelCAS}, this
     * method can return one of the type codes TYPE_CLASS_INTLIST, TYPE_CLASS_FLOATLIST,
     * TYPE_CLASS_STRINGLIST, or TYPE_CLASS_FSLIST.
     * 
     * @param type
     *          the type to classify
     * @return one of the TYPE_CLASS codes defined on {@link LowLevelCAS} or on this interface.
     */
    final int classifyType(int type) {
      // For most most types
      if (listUtils.isIntListType(type)) {
        return TYPE_CLASS_INTLIST;
      }
      if (listUtils.isFloatListType(type)) {
        return TYPE_CLASS_FLOATLIST;
      }
      if (listUtils.isStringListType(type)) {
        return TYPE_CLASS_STRINGLIST;
      }
      if (listUtils.isFsListType(type)) {
        return TYPE_CLASS_FSLIST;
      }
      return cas.ll_getTypeClass(type);
    }

    int getElementCountForSharedData() {
      return (sharedData == null) ? 0 : sharedData.getOutOfTypeSystemElements().size();
    }    
    
    /**
     * Get the XMI ID to use for an FS.
     * 
     * @param addr
     *          address of FS
     * @return XMI ID. If addr == CASImpl.NULL, returns null
     */
    String getXmiId(int addr) {
      int v = getXmiIdAsInt(addr);
      return (v == 0) ? null : Integer.toString(v);
    }
    
    int getXmiIdAsInt(int addr) {
      if (addr == CASImpl.NULL) {
        return 0;
      }
      if (isFiltering) { // return as null any references to types not in target TS
        String typeName = tsi.ll_getTypeForCode(cas.getHeapValue(addr)).getName();
        if (filterTypeSystem.getType(typeName) == null) {
          return 0;
        }
      }
      
      if (sharedData == null) {
        // in the absence of outside information, just use the FS address
        return addr;
      } else {
        return sharedData.getXmiIdAsInt(addr);
      }
      
    }

    String getNameSpacePrefix(String uimaTypeName, String nsUri, int lastDotIndex) {
      // determine what namespace prefix to use
      String prefix = (String) nsUriToPrefixMap.get(nsUri);
      if (prefix == null) {
        if (lastDotIndex != -1) { // have namespace 
          int secondLastDotIndex = uimaTypeName.lastIndexOf('.', lastDotIndex-1);
          prefix = uimaTypeName.substring(secondLastDotIndex + 1, lastDotIndex);
        } else {
          prefix = "noNamespace"; // is correct for older XMI standard too
        }
        // make sure this prefix hasn't already been used for some other namespace
        // including out-of-type-system types (for XmiCasSerializer)
        if (nsPrefixesUsed.contains(prefix)) {
          String basePrefix = prefix;
          int num = 2;
          while (nsPrefixesUsed.contains(basePrefix + num)) {
            num++;
          }
          prefix = basePrefix + num;
        }
        nsUriToPrefixMap.put(nsUri, prefix);
        nsPrefixesUsed.add(prefix);
      }
      return prefix;
    }
    /*
     *  convert to shared string, without interning, reduce GCs
     */
    String getUniqueString(String s) { 
      String u = uniqueStrings.get(s);
      if (null == u) {
        u = s;
        uniqueStrings.put(s, s);
      }
      return u;
    }
    
    public String getTypeNameFromXmlElementName(XmlElementName xe) {
      final String nsUri = xe.nsUri;
      if (nsUri == null || nsUri.length() == 0) {
        throw new UnsupportedOperationException();
      }
      
      final int pfx = XmiCasSerializer.URIPFX.length;
      final int sfx = XmiCasSerializer.URISFX.length;
      
      String r = (nsUri.startsWith(XmiCasSerializer.DEFAULT_NAMESPACE_URI)) ? 
          "" :
          nsUri.substring(XmiCasSerializer.URIPFX.length, nsUri.length() - sfx);
      r = r.replace('/', '.');
      
      return r + xe.localName;
    }

  }  
}
