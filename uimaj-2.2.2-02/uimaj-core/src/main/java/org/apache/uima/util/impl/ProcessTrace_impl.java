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

package org.apache.uima.util.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Stack;

import org.apache.uima.UIMAFramework;
import org.apache.uima.UIMA_IllegalStateException;
import org.apache.uima.util.ProcessTrace;
import org.apache.uima.util.ProcessTraceEvent;
import org.apache.uima.util.UimaTimer;

/**
 * Reference implementation of {@link ProcessTrace}.
 * 
 * 
 */
public class ProcessTrace_impl implements ProcessTrace {
  private static final long serialVersionUID = 7566277176545062757L;

  /**
   * List of closed events.
   */
  private List mEventList = new ArrayList();

  /**
   * Stack of open events.
   */
  private Stack mOpenEvents = new Stack();

  /**
   * Timer class used to get timing information.
   */
  private UimaTimer mTimer;

  /**
   * Indicates whether process trace is enabled.
   */
  private boolean mEnabled;

  /**
   * Create a ProcessTrace_impl using the framework's default timer.
   */
  public ProcessTrace_impl() {
    this(UIMAFramework.getDefaultPerformanceTuningProperties());
  }

  /**
   * Create a ProcessTrace_impl using the framework's default timer.
   * 
   * @param aPerformanceTuningSettings
   *          performance tuning settings. One of the settings allows the ProcessTrace to be
   *          disabled.
   */
  public ProcessTrace_impl(Properties aPerformanceTuningSettings) {
    if (aPerformanceTuningSettings == null) {
      aPerformanceTuningSettings = UIMAFramework.getDefaultPerformanceTuningProperties();
    }
    mEnabled = "true".equalsIgnoreCase(aPerformanceTuningSettings
            .getProperty(UIMAFramework.PROCESS_TRACE_ENABLED));
    if (mEnabled) {
      mTimer = UIMAFramework.newTimer();
    }
  }

  /**
   * Create a ProcessTrace_impl with a custom timer.
   * 
   * @param aTimer
   *          the timer to use for collecting performance stats
   */
  public ProcessTrace_impl(UimaTimer aTimer) {
    this(aTimer, UIMAFramework.getDefaultPerformanceTuningProperties());
  }

  /**
   * Create a ProcessTrace_impl with a custom timer.
   * 
   * @param aTimer
   *          the timer to use for collecting performance stats
   * @param aPerformanceTuningSettings
   *          performance tuning settings. One of the settings allows the ProcessTrace to be
   *          disabled.
   */
  public ProcessTrace_impl(UimaTimer aTimer, Properties aPerformanceTuningSettings) {
    mTimer = aTimer;
    if (aPerformanceTuningSettings == null) {
      aPerformanceTuningSettings = UIMAFramework.getDefaultPerformanceTuningProperties();
    }
    mEnabled = "true".equalsIgnoreCase(aPerformanceTuningSettings
            .getProperty(UIMAFramework.PROCESS_TRACE_ENABLED));
  }

  /**
   * @see org.apache.uima.util.ProcessTrace#startEvent(java.lang.String, java.lang.String,
   *      java.lang.String)
   */
  public void startEvent(String aComponentName, String aEventType, String aDescription) {
    if (mEnabled) {
      // DEBUG System.out.println("startEvent(" + aComponentName + "," + aEventType + ")");
      ProcessTraceEvent_impl evt = new ProcessTraceEvent_impl(aComponentName, aEventType,
              aDescription);
      evt.setStartTime(mTimer.getTimeInMillis());
      mOpenEvents.push(evt);
    }
  }

  /**
   * @see org.apache.uima.util.ProcessTrace#endEvent(java.lang.String, java.lang.String,
   *      java.lang.String)
   */
  public void endEvent(String aComponentName, String aEventType, String aResultMessage) {
    if (mEnabled) {
      // DEBUG System.out.println("endEvent(" + aComponentName + "," + aEventType + ")");

      // look for matching event on mOpenEvents stack. If found, close it and
      // all its open sub-events. If not found, throw exception.
      ArrayList eventsToClose = new ArrayList();
      boolean foundEvent = false;
      while (!mOpenEvents.isEmpty()) {
        ProcessTraceEvent_impl evt = (ProcessTraceEvent_impl) mOpenEvents.pop();
        eventsToClose.add(evt);
        if (aComponentName.equals(evt.getComponentName()) && aEventType.equals(evt.getType())) {
          foundEvent = true;
          break;
        }
      }

      if (foundEvent) // found matching event
      {
        // close all open sub-events
        long currentTime = mTimer.getTimeInMillis();
        for (int i = 0; i < eventsToClose.size(); i++) {
          ProcessTraceEvent_impl subEvt = (ProcessTraceEvent_impl) eventsToClose.get(i);
          subEvt.setResultMessage(aResultMessage);
          subEvt.setDuration((int) (currentTime - subEvt.getStartTime()));

          ProcessTraceEvent_impl owner = null;
          if (i < eventsToClose.size() - 1) {
            owner = (ProcessTraceEvent_impl) eventsToClose.get(i + 1);
          } else if (!mOpenEvents.isEmpty()) {
            owner = (ProcessTraceEvent_impl) mOpenEvents.peek();
          }

          if (owner != null) {
            owner.addSubEvent(subEvt);
          } else // top-level event has closed, add to the Event List
          {
            mEventList.add(subEvt);
          }
        }
      } else // no matching event
      {
        // restore stack
        for (int i = 0; i < eventsToClose.size(); i++) {
          mOpenEvents.push(eventsToClose.get(i));
        }
        // throw exception
        throw new UIMA_IllegalStateException(UIMA_IllegalStateException.REQUIRED_METHOD_CALL,
                new Object[] { "startEvent", "endEvent" });
      }
    }
  }

  /**
   * @see org.apache.uima.util.ProcessTrace#addEvent(String, String, String, int, String)
   */
  public void addEvent(String aComponentName, String aType, String aDescription, int aDuration,
          String aResultMsg) {
    if (mEnabled) {
      // create event
      ProcessTraceEvent_impl evt = new ProcessTraceEvent_impl(aComponentName, aType, aDescription);
      evt.setDuration(aDuration);
      evt.setResultMessage(aResultMsg);

      // add
      addEvent(evt);
    }
  }

  /**
   * @see org.apache.uima.util.ProcessTrace#addEvent(org.apache.uima.util.ProcessTraceEvent)
   */
  public void addEvent(ProcessTraceEvent aEvent) {
    if (mEnabled) {
      if (!mOpenEvents.isEmpty()) {
        ProcessTraceEvent_impl owner = (ProcessTraceEvent_impl) mOpenEvents.peek();
        owner.addSubEvent(aEvent);
      } else // top-level event has closed, add to the Event List
      {
        mEventList.add(aEvent);
      }
    }
  }

  /**
   * @see org.apache.uima.util.ProcessTrace#addAll(java.util.List)
   */
  public void addAll(List aEventList) {
    Iterator it = aEventList.iterator();
    while (it.hasNext()) {
      ProcessTraceEvent evt = (ProcessTraceEvent) it.next();
      addEvent(evt);
    }
  }

  /**
   * @see org.apache.uima.util.ProcessTrace#getEvents()
   */
  public List getEvents() {
    return mEventList;
  }

  /**
   * @see org.apache.uima.util.ProcessTrace#getEventsByComponentName(String)
   */
  public List getEventsByComponentName(String aComponentName, boolean aRecurseAfterMatch) {
    List result = new ArrayList();
    Iterator it = getEvents().iterator();
    while (it.hasNext()) {
      ProcessTraceEvent event = (ProcessTraceEvent) it.next();
      getEventsByComponentName(event, aComponentName, aRecurseAfterMatch, result);
    }
    return result;
  }

  /**
   * @see org.apache.uima.util.ProcessTrace#getEventsByType(String)
   */
  public List getEventsByType(String aType, boolean aRecurseAfterMatch) {
    List result = new ArrayList();
    Iterator it = getEvents().iterator();
    while (it.hasNext()) {
      ProcessTraceEvent event = (ProcessTraceEvent) it.next();
      getEventsByType(event, aType, aRecurseAfterMatch, result);
    }
    return result;
  }

  /**
   * @see org.apache.uima.util.ProcessTrace#getEvent(String, String)
   */
  public ProcessTraceEvent getEvent(String aComponentName, String aType) {
    List events = getEvents();
    return getEvent(events, aComponentName, aType);
  }

  protected ProcessTraceEvent getEvent(List aEvents, String aComponentName, String aType) {
    Iterator it = aEvents.iterator();
    while (it.hasNext()) {
      ProcessTraceEvent event = (ProcessTraceEvent) it.next();
      if (aComponentName.equals(event.getComponentName()) && aType.equals(event.getType())) {
        return event;
      } else {
        ProcessTraceEvent matchingSubEvt = getEvent(event.getSubEvents(), aComponentName, aType);
        if (matchingSubEvt != null) {
          return matchingSubEvt;
        }
      }
    }
    return null;
  }

  /**
   * @see org.apache.uima.util.ProcessTrace#clear()
   */
  public void clear() {
    mEventList.clear();
  }

  /**
   * @see org.apache.uima.util.ProcessTrace#aggregate(org.apache.uima.util.ProcessTrace)
   */
  public void aggregate(ProcessTrace aProcessTrace) {
    if (mEnabled) {
      List newEventList = aProcessTrace.getEvents();

      // iterate over new events
      Iterator newEventIter = newEventList.iterator();
      while (newEventIter.hasNext()) {
        ProcessTraceEvent_impl newEvt = (ProcessTraceEvent_impl) newEventIter.next();
        // find corresponding event in thisEventList
        ProcessTraceEvent_impl correspondingEvt = findCorrespondingEvent(mEventList, newEvt);
        if (correspondingEvt != null) {
          aggregateEvent(correspondingEvt, newEvt);
        } else {
          // no corresponding event - add newEvt to list of events to be added
          mEventList.add(newEvt);
        }
      }
    }
  }

  /**
   * @see org.apache.uima.util.ProcessTrace#toString()
   */
  public String toString() {
    // count total time so we can do percentages
    int totalTime = 0;
    Iterator it = mEventList.iterator();
    while (it.hasNext()) {
      ProcessTraceEvent event = (ProcessTraceEvent) it.next();
      totalTime += event.getDuration();
    }

    // go back through and generate string
    StringBuffer buf = new StringBuffer();
    it = mEventList.iterator();
    while (it.hasNext()) {
      ProcessTraceEvent event = (ProcessTraceEvent) it.next();
      event.toString(buf, 0, totalTime);
    }

    return buf.toString();
  }

  /**
   * Utility method used by getEventsByComponentName(String)
   */
  protected void getEventsByComponentName(ProcessTraceEvent aEvent, String aComponentName,
          boolean aRecurseAfterMatch, List aResultList) {
    if (aComponentName.equals(aEvent.getComponentName())) {
      aResultList.add(aEvent);
      if (!aRecurseAfterMatch) {
        return;
      }
    }

    // recurse into child events
    Iterator it = aEvent.getSubEvents().iterator();
    while (it.hasNext()) {
      ProcessTraceEvent event = (ProcessTraceEvent) it.next();
      getEventsByComponentName(event, aComponentName, aRecurseAfterMatch, aResultList);
    }
  }

  /**
   * Utility method used by getEventsByType(String)
   */
  protected void getEventsByType(ProcessTraceEvent aEvent, String aType,
          boolean aRecurseAfterMatch, List aResultList) {
    if (aType.equals(aEvent.getType())) {
      aResultList.add(aEvent);
      if (!aRecurseAfterMatch) {
        return;
      }
    }

    // recurse into child events
    Iterator it = aEvent.getSubEvents().iterator();
    while (it.hasNext()) {
      ProcessTraceEvent event = (ProcessTraceEvent) it.next();
      getEventsByType(event, aType, aRecurseAfterMatch, aResultList);
    }
  }

  /**
   * Utility method used by aggregate(ProcessTrace)
   */
  protected ProcessTraceEvent_impl findCorrespondingEvent(List aEventList,
          ProcessTraceEvent_impl aEvent) {
    Iterator it = aEventList.iterator();
    while (it.hasNext()) {
      ProcessTraceEvent_impl evt = (ProcessTraceEvent_impl) it.next();
      if (evt.getComponentName().equals(aEvent.getComponentName())
              && evt.getType().equals(aEvent.getType())) {
        return evt;
      }
    }
    return null;
  }

  /**
   * Utility method used by aggregate(ProcessTrace)
   */
  protected void aggregateEvent(ProcessTraceEvent_impl aDest, ProcessTraceEvent_impl aSrc) {
    // sum durations
    aDest.addToDuration(aSrc.getDuration());
    // update result msg
    aDest.setResultMessage(aSrc.getResultMessage());

    // aggregate sub-events

    List destEventList = aDest.getSubEvents();
    List srcEventList = aSrc.getSubEvents();
    List eventsToAdd = null; // lazy init

    // iterate over src events
    Iterator srcEventIter = srcEventList.iterator();
    while (srcEventIter.hasNext()) {
      ProcessTraceEvent_impl srcEvt = (ProcessTraceEvent_impl) srcEventIter.next();
      // find corresponding event in destEventList
      ProcessTraceEvent_impl correspondingEvt = findCorrespondingEvent(destEventList, srcEvt);
      if (correspondingEvt != null) {
        aggregateEvent(correspondingEvt, srcEvt);
      } else {
        // no corresponding event - add srcEvt to list of events to be added
        if (eventsToAdd == null) {
          eventsToAdd = new ArrayList();
        }
        eventsToAdd.add(srcEvt);
      }
    }

    // add all from events eventsToAdd
    if (eventsToAdd != null) {
      Iterator it = eventsToAdd.iterator();
      while (it.hasNext()) {
        aDest.addSubEvent((ProcessTraceEvent) it.next());
      }
    }
  }
}
