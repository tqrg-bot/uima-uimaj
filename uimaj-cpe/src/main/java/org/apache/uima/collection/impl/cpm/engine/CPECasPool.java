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

package org.apache.uima.collection.impl.cpm.engine;

import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Vector;

import org.apache.uima.UIMAFramework;
import org.apache.uima.cas.CAS;
import org.apache.uima.collection.impl.cpm.utils.CPMUtils;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.resource.ResourceManager;
import org.apache.uima.util.CasCreationUtils;
import org.apache.uima.util.Level;

/**
 * Implements object pooling mechanism to limit number of CAS instances. Cas'es are checked out,
 * used and checked back in when done.
 * 
 * 
 * 
 * 
 * 
 */
public class CPECasPool {

  private Vector mAllInstances = new Vector();

  private Vector mFreeInstances = new Vector();

  private LinkedList checkedOutInstances = new LinkedList();

  private int mNumInstances;

  /**
   * Initialize the pool.
   * 
   * @param aNumInstances -
   *          max size of the pool
   * @param aList -
   *          aggregate type system
   * @param aResourceManager -
   *          resource manager, needed for JCAS class loading
   * @throws ResourceInitializationException
   */
  public CPECasPool(int aNumInstances, List aList, ResourceManager aResourceManager)
                  throws ResourceInitializationException {
    mNumInstances = aNumInstances;
    fillPool(aList, UIMAFramework.getDefaultPerformanceTuningProperties(), aResourceManager);
  }

  /**
   * Initialize the pool
   * 
   * @param aNumInstances -
   *          max size of the pool
   * @param aList - -
   *          aggregate type system
   * @param aPerformanceTuningSettings
   * @param aResourceManager -
   *          resource manager, needed for JCAS class loading
   * @throws ResourceInitializationException
   */
  public CPECasPool(int aNumInstances, List aList, Properties aPerformanceTuningSettings,
                  ResourceManager aResourceManager) throws ResourceInitializationException {
    mNumInstances = aNumInstances;
    fillPool(aList, aPerformanceTuningSettings, aResourceManager);
  }

  /**
   * Fills the pool with initialized instances of CAS.
   * 
   * @param aList -
   *          aggregate type system
   * @param aPerformanceTuningSettings
   * @param aResourceManager -
   *          resource manager, needed for JCAS class loading
   * @throws ResourceInitializationException
   */
  protected void fillPool(List aList, Properties aPerformanceTuningSettings,
                  ResourceManager aResourceManager) throws ResourceInitializationException {
    // create first CAS from metadata
    CAS c0 = CasCreationUtils.createCas(aList, aPerformanceTuningSettings, aResourceManager);
    mAllInstances.add(c0);
    mFreeInstances.add(c0);
    // create additional CASes that share same type system
    for (int i = 1; i < mNumInstances; i++) {
      CAS c = CasCreationUtils.createCas(aList, c0.getTypeSystem(), aPerformanceTuningSettings,
                      aResourceManager);
      mAllInstances.add(c);
      mFreeInstances.add(c);
    }
  }

  /**
   * Returns a Cas instance from the pool. This routine waits for a free instance of Cas a given
   * amount of time. If free instance is not available this routine returns null.
   * 
   * @param aTimeout -
   *          max amount of time in millis to wait for CAS instance
   * @return - CAS instance, or null on timeout
   */
  public synchronized CAS getCas(long aTimeout) {
    CAS cas;

    if ((cas = getCas()) == null) {
      try {
        this.wait(aTimeout);
      } catch (InterruptedException e) {
      }
      cas = getCas();
    }
    return cas;
  }

  /**
   * Checks out a CAS from the pool.
   * 
   * @return a CAS instance. Returns <code>null</code> if none are available (in which case the
   *         client may {@link Object#wait()} on this object in order to be notified when an
   *         instance becomes available).
   */
  public synchronized CAS getCas() {
    if (!mFreeInstances.isEmpty()) {
      CAS cas = (CAS) mFreeInstances.remove(0);
      if (cas != null) {
        // Add the cas to a list of checked-out cases
        checkedOutInstances.add(cas);
        if (UIMAFramework.getLogger().isLoggable(Level.FINEST)) {
          UIMAFramework.getLogger(this.getClass()).logrb(
                          Level.FINEST,
                          this.getClass().getName(),
                          "process",
                          CPMUtils.CPM_LOG_RESOURCE_BUNDLE,
                          "UIMA_CPM_add_cas_to_checkedout_list__FINEST",
                          new Object[] { Thread.currentThread().getName(),
                              String.valueOf(checkedOutInstances.size()) });

        }
      }
      return cas;
    } else {
      // no instances available
      return null;
    }
  }

  /**
   * Checks in a CAS to the pool. This automatically calls the {@link CAS#reset()} method, to ensure
   * that when the CAS is later retrieved from the pool it will be ready to use. Also notifies other
   * Threads that may be waiting for an instance to become available.
   * 
   * @param aCas
   *          the CAS to release
   */
  public synchronized void releaseCas(CAS aCas) {
    // make sure this CAS actually belongs to this pool and is checked out
    if (!mAllInstances.contains(aCas) || mFreeInstances.contains(aCas)) {
      if (UIMAFramework.getLogger().isLoggable(Level.WARNING)) {
        UIMAFramework.getLogger(this.getClass()).logrb(Level.WARNING, this.getClass().getName(),
                        "process", CPMUtils.CPM_LOG_RESOURCE_BUNDLE,
                        "UIMA_CPM_invalid_checkin__WARNING",
                        new Object[] { Thread.currentThread().getName() });
      }
    } else {
      // reset CAS
      aCas.reset();
      // Add the CAS to the end of the free instances List
      mFreeInstances.add(aCas);

      // get the position of the CAS in the list.
      int index = checkedOutInstances.indexOf(aCas); // new code JC 05/11/2005
      if (index != -1) {
        checkedOutInstances.remove(index);
        if (UIMAFramework.getLogger().isLoggable(Level.FINEST)) {
          UIMAFramework.getLogger(this.getClass()).logrb(
                          Level.FINEST,
                          this.getClass().getName(),
                          "process",
                          CPMUtils.CPM_LOG_RESOURCE_BUNDLE,
                          "UIMA_CPM_removed_from_checkedout_list__FINEST",
                          new Object[] { Thread.currentThread().getName(),
                              String.valueOf(checkedOutInstances.size()) });
        }
      }

      if (UIMAFramework.getLogger().isLoggable(Level.FINEST)) {
        UIMAFramework.getLogger(this.getClass()).logrb(
                        Level.FINEST,
                        this.getClass().getName(),
                        "process",
                        CPMUtils.CPM_LOG_RESOURCE_BUNDLE,
                        "UIMA_CPM_return_cas_to_pool__FINEST",
                        new Object[] { Thread.currentThread().getName(),
                            String.valueOf(checkedOutInstances.size()) });
      }
    }

  }

  /**
   * Returns number of CAS'es that have been checked out from pool
   * 
   * @return - number of CAS'es being processed
   */
  public synchronized int getCheckedOutCasCount() {
    return checkedOutInstances.size();
  }

  /**
   * Returns a CAS found in a given position in the list.
   * 
   * @param aIndex -
   *          position of the CAS in the list
   * 
   * @return CAS - reference to a CAS
   */
  public synchronized CAS getCheckedOutCas(int aIndex) {
    if (aIndex > checkedOutInstances.size()) {
      return null;
    }
    return (CAS) checkedOutInstances.get(aIndex);
  }

  /**
   * Gets the size of this pool (the total number of CAS instances that it can hold).
   * 
   * @return the size of this pool
   */
  public int getSize() {
    return mNumInstances;
  }

  /**
   * Returns pool capacity
   * 
   * @return - size of the pool
   */
  protected Vector getAllInstances() {
    return mAllInstances;
  }

  /**
   * Number of free Cas'es available in the pool
   * 
   * @return
   */
  protected Vector getFreeInstances() {
    return mFreeInstances;
  }

}
