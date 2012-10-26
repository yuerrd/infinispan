/*
 * JBoss, Home of Professional Open Source
 * Copyright 2009 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.factories;

import org.infinispan.CacheException;
import org.infinispan.config.Configuration;
import org.infinispan.config.ConfigurationException;
import org.infinispan.factories.annotations.DefaultFactoryFor;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.SurvivesRestarts;
import org.infinispan.factories.components.ComponentMetadata;
import org.infinispan.factories.components.ComponentMetadataRepo;
import org.infinispan.factories.scopes.Scope;
import org.infinispan.factories.scopes.Scopes;
import org.infinispan.lifecycle.ComponentStatus;
import org.infinispan.lifecycle.Lifecycle;
import org.infinispan.util.ReflectionUtil;
import org.infinispan.util.Util;
import org.infinispan.util.logging.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * A registry where components which have been created are stored.  Components are stored as singletons, registered
 * under a specific name.
 * <p/>
 * Components can be retrieved from the registry using {@link #getComponent(Class)}.
 * <p/>
 * Components can be registered using {@link #registerComponent(Object, Class)}, which will cause any dependencies to be
 * wired in as well.  Components that need to be created as a result of wiring will be done using {@link
 * #getOrCreateComponent(Class)}, which will look up the default factory for the component type (factories annotated
 * with the appropriate {@link DefaultFactoryFor} annotation.
 * <p/>
 * Default factories are treated as components too and will need to be wired before being used.
 * <p/>
 * The registry can exist in one of several states, as defined by the {@link org.infinispan.lifecycle.ComponentStatus}
 * enumeration. In terms of the cache, state changes in the following manner: <ul> <li>INSTANTIATED - when first
 * constructed</li> <li>CONSTRUCTED - when created using the DefaultCacheFactory</li> <li>STARTED - when {@link
 * org.infinispan.Cache#start()} is called</li> <li>STOPPED - when {@link org.infinispan.Cache#stop()} is called</li>
 * </ul>
 * <p/>
 * Cache configuration can only be changed and will only be re-injected if the cache is not in the {@link
 * org.infinispan.lifecycle.ComponentStatus#RUNNING} state.
 *
 * Thread Safety: instances of {@link GlobalComponentRegistry} can be concurrently updated so all
 * the write operations are serialized through class intrinsic lock.
 *
 * @author Manik Surtani
 * @author Galder Zamarreño
 * @since 4.0
 */
@SurvivesRestarts
@Scope(Scopes.NAMED_CACHE)
public abstract class AbstractComponentRegistry implements Lifecycle, Cloneable {

   private static final String DEPENDENCIES_ENABLE_JVMOPTION = "infinispan.debugDependencies";

   /**
    * Set the system property <li>infinispan.debugDependencies</li> to <li>true</li> to enable some extra information to
    * errors generated by the component factory.
    */
   public static final boolean DEBUG_DEPENDENCIES = Boolean.getBoolean(DEPENDENCIES_ENABLE_JVMOPTION);
   private Stack<String> debugStack = DEBUG_DEPENDENCIES ? new Stack<String>() : null;

   /**
    * Contains class definitions of component factories that can be used to construct certain components
    */
//   private Map<String, Class<? extends AbstractComponentFactory>> defaultFactories = null;

   private static final Object NULL_COMPONENT = new Object();

   // component and method containers
   private final ConcurrentMap<String, Component> componentLookup = new ConcurrentHashMap<String, AbstractComponentRegistry.Component>(1);

   protected volatile ComponentStatus state = ComponentStatus.INSTANTIATED;

   private static final PrioritizedMethod[] EMPTY_PRIO_METHODS = {};

   /**
    * Retrieves the state of the registry
    *
    * @return state of the registry
    */
   public ComponentStatus getStatus() {
      return state;
   }

   protected abstract Log getLog();

   public abstract ComponentMetadataRepo getComponentMetadataRepo();

   /**
    * Wires an object instance with dependencies annotated with the {@link Inject} annotation, creating more components
    * as needed based on the Configuration passed in if these additional components don't exist in the {@link
    * ComponentRegistry}.  Strictly for components that don't otherwise live in the registry and have a lifecycle, such
    * as Commands.
    *
    * @param target object to wire
    * @throws ConfigurationException if there is a problem wiring the instance
    */
   public void wireDependencies(Object target) throws ConfigurationException {
      try {
         Class<?> targetClass = target.getClass();
         ComponentMetadata metadata = getComponentMetadataRepo().findComponentMetadata(targetClass);
         if (metadata != null && metadata.getInjectMethods() != null && metadata.getInjectMethods().length != 0) {
            // search for anything we need to inject
            for (ComponentMetadata.InjectMetadata injectMetadata : metadata.getInjectMethods()) {
               Class<?>[] methodParameters = injectMetadata.getParameterClasses();
               if (methodParameters == null) {
                  methodParameters = ReflectionUtil.toClassArray(injectMetadata.getParameters());
                  injectMetadata.setParameterClasses(methodParameters);
               }

               Method method = injectMetadata.getMethod();
               if (method == null) {
                  method = ReflectionUtil.findMethod(targetClass, injectMetadata.getMethodName(), methodParameters);
                  injectMetadata.setMethod(method);
               }
               invokeInjectionMethod(target, injectMetadata);
            }
         }
      } catch (Exception e) {
         throw new ConfigurationException("Unable to configure component (type: " + target.getClass() + ", instance " + target + ")", e);
      }
   }

   /**
    * Registers a component in the registry under the given type, and injects any dependencies needed.  If a component
    * of this type already exists, it is overwritten.
    *
    * @param component component to register
    * @param type      type of component
    */
   public synchronized final void registerComponent(Object component, Class<?> type) {
      registerComponent(component, type.getName(), type.equals(component.getClass()));
   }

   public synchronized final void registerComponent(Object component, String name) {
      registerComponent(component, name, name.equals(component.getClass().getName()));
   }

   public synchronized final void registerComponent(Object component, String name, boolean nameIsFQCN) {
      registerComponentInternal(component, name, nameIsFQCN);
   }

   protected synchronized final void registerNonVolatileComponent(Object component, String name) {
      registerComponentInternal(component, name, false);
   }

   protected synchronized final void registerNonVolatileComponent(Object component, Class<?> type) {
      registerComponentInternal(component, type.getName(), true);
   }

   protected synchronized void registerComponentInternal(Object component, String name, boolean nameIsFQCN) {
      if (component == null)
         throw new NullPointerException("Cannot register a null component under name [" + name + "]");
      Component old = componentLookup.get(name);

      if (old != null) {
         // if they are equal don't bother
         if (old.instance.equals(component)) {
            getLog().tracef("Attempting to register a component equal to one that already exists under the same name (%s).  Not doing anything.", name);
            return;
         }
      }

      Component c;
      if (old != null) {
         getLog().tracef("Replacing old component %s with new instance %s", old, component);
         old.instance = component;
         old.methodsScanned = false;
         c = old;
      } else {
         c = new Component();
         c.name = name;
         c.instance = component;
         componentLookup.put(name, c);
      }

      c.metadata = getComponentMetadataRepo().findComponentMetadata(component.getClass());
      try {
         c.buildInjectionMethodsList();
      } catch (ClassNotFoundException cnfe) {
         throw new CacheException("Error injecting dependencies for component " + name, cnfe);
      }
      // inject dependencies for this component
      // we inject dependencies only after the component is already in the map to support cyclical dependencies
      c.injectDependencies();

      if (old == null) getLog().tracef("Registering component %s under name %s", c, name);
      if (state == ComponentStatus.RUNNING) {
         populateLifeCycleMethods(c);
         try {
            invokeStartMethods(Arrays.asList(c.startMethods));
         } catch (Throwable t) {
            // the component hasn't started properly, remove its registration
            componentLookup.remove(name);
            // the caller will log the exception
            handleLifecycleTransitionFailure(t);
         }
      }
   }

   @SuppressWarnings("unchecked")
   private void invokeInjectionMethod(Object o, ComponentMetadata.InjectMetadata injectMetadata) {
      Class<?>[] dependencies = injectMetadata.getParameterClasses();
      if (dependencies.length > 0) {
         Object[] params = new Object[dependencies.length];
         if (getLog().isTraceEnabled())
            getLog().tracef("Injecting dependencies for method [%s] on an instance of [%s].", injectMetadata.getMethod(), o.getClass().getName());
         for (int i = 0; i < dependencies.length; i++) {
            String name = injectMetadata.getParameterName(i);
            boolean nameIsFQCN = !injectMetadata.isParameterNameSet(i);
            params[i] = getOrCreateComponent(dependencies[i], name, nameIsFQCN);
         }
         ReflectionUtil.invokeAccessibly(o, injectMetadata.getMethod(), params);
      }
   }

   /**
    * Retrieves a component if one exists, and if not, attempts to find a factory capable of constructing the component
    * (factories annotated with the {@link DefaultFactoryFor} annotation that is capable of creating the component
    * class).
    * <p/>
    * If an instance needs to be constructed, dependencies are then automatically wired into the instance, based on
    * methods on the component type annotated with {@link Inject}.
    * <p/>
    * Summing it up, component retrieval happens in the following order:<br /> 1.  Look for a component that has already
    * been created and registered. 2.  Look for an appropriate component that exists in the {@link Configuration} that
    * may be injected from an external system. 3.  Look for a class definition passed in to the {@link Configuration} -
    * such as an EvictionPolicy implementation 4.  Attempt to create it by looking for an appropriate factory (annotated
    * with {@link DefaultFactoryFor})
    * <p/>
    *
    * @param componentClass type of component to be retrieved.  Should not be null.
    * @return a fully wired component instance, or null if one cannot be found or constructed.
    * @throws ConfigurationException if there is a problem with constructing or wiring the instance.
    */
   protected synchronized <T> T getOrCreateComponent(Class<T> componentClass) {
      return getOrCreateComponent(componentClass, componentClass.getName(), true);
   }

   protected <T> T getOrCreateComponent(Class<T> componentClass, String name) {
      return getOrCreateComponent(componentClass, name, false);
   }

   @SuppressWarnings("unchecked")
   protected synchronized <T> T getOrCreateComponent(Class<T> componentClass, String name, boolean nameIsFQCN) {
      if (DEBUG_DEPENDENCIES) debugStack.push(name);

      Object component;
      Component oldWrapper = lookupComponent(componentClass.getName(), name, nameIsFQCN);
      if (oldWrapper != null) {
         component = unwrapComponent(oldWrapper);
      } else {
         // create this component and add it to the registry
         AbstractComponentFactory factory = getFactory(componentClass);
         component = factory instanceof NamedComponentFactory ?
               ((NamedComponentFactory) factory).construct(componentClass, name)
               : factory.construct(componentClass);

         if (component != null) {
            registerComponent(component, name, nameIsFQCN);
         } else {
            getLog().tracef("Registering a null for component %s", name);
            registerNullComponent(name);
         }
      }

      if (DEBUG_DEPENDENCIES) debugStack.pop();
      return (T) component;
   }

   /**
    * Retrieves a component factory instance capable of constructing components of a specified type.  If the factory
    * doesn't exist in the registry, one is created.
    *
    * @param componentClass type of component to construct
    * @return component factory capable of constructing such components
    */
   protected AbstractComponentFactory getFactory(Class<?> componentClass) {
      String cfClass = getComponentMetadataRepo().findFactoryForComponent(componentClass);
      if (cfClass == null) {
         throwStackAwareConfigurationException("No registered default factory for component '" + componentClass + "' found!");
      }
      // a component factory is a component too!  See if one has been created and exists in the registry
      AbstractComponentFactory cf = getComponent(cfClass);
      if (cf == null) {
         cf = createComponentFactoryInternal(componentClass, cfClass);
      }

      // ensure the component factory is in the STARTED state!
      Component c = lookupComponent(cfClass, cfClass, true);
      if (c.instance != cf)
         throwStackAwareConfigurationException("Component factory " + cfClass + " incorrectly registered!");
      return cf;
   }

   protected synchronized AbstractComponentFactory createComponentFactoryInternal(Class<?> componentClass, String cfClass) {
      //first check as it might have been created in between by another thread
      AbstractComponentFactory component = getComponent(cfClass);
      if (component != null) return component;

      //hasn't yet been created.  Create and put in registry
      AbstractComponentFactory cf = instantiateFactory(cfClass);
      if (cf == null)
         throwStackAwareConfigurationException("Unable to locate component factory for component " + componentClass);
      // we simply register this factory.  Registration will take care of constructing any dependencies.
      registerComponent(cf, cfClass);
      return cf;
   }

   protected Component lookupComponent(String componentClassName, String componentName, boolean nameIsFQCN) {
      return componentLookup.get(componentName);
   }

   /**
    * No such thing as a meta factory yet.  Factories are created using this method which attempts to use an empty
    * public constructor.
    *
    * @param factoryName classname of factory to be created
    * @return factory instance
    */
   AbstractComponentFactory instantiateFactory(String factoryName) {
      Class<?> factory = Util.loadClass(factoryName, getClass().getClassLoader());
      if (AutoInstantiableFactory.class.isAssignableFrom(factory)) {
         try {
            return (AbstractComponentFactory) factory.newInstance();
         } catch (Exception e) {
            // unable to get a hold of an instance!!
            throw new ConfigurationException("Unable to instantiate factory " + factory + "  Debug stack: " + debugStack, e);
         }
      } else {
         throw new ConfigurationException("Cannot auto-instantiate factory " + factory + " as it doesn't implement " + AutoInstantiableFactory.class.getSimpleName() + "!  Debug stack: " + debugStack);
      }
   }

   /**
    * registers a special "null" component that has no dependencies.
    *
    * @param name name of component to register as a null
    */
   protected synchronized final void registerNullComponent(String name) {
      registerComponent(NULL_COMPONENT, name, false);
   }

   /**
    * Retrieves the configuration component.
    *
    * @return a Configuration object
    */
   protected Configuration getConfiguration() {
      // this is assumed to always be present as a part of the bootstrap/construction of a ComponentRegistry.
      return getComponent(Configuration.class);
   }

   /**
    * Retrieves a component of a specified type from the registry, or null if it cannot be found.
    *
    * @param type type to find
    * @return component, or null
    */
   @SuppressWarnings("unchecked")
   public <T> T getComponent(Class<T> type) {
      String className = type.getName();
      return (T) getComponent(className, className, true);
   }

   @SuppressWarnings("unchecked")
   public <T> T getComponent(String componentClassName) {
      return (T) getComponent(componentClassName, componentClassName, true);
   }

   @SuppressWarnings("unchecked")
   public <T> T getComponent(String componentClassName, String name) {
      return (T) getComponent(componentClassName, name, false);
   }

   @SuppressWarnings("unchecked")
   public <T> T getComponent(Class<T> componentClass, String name) {
      return (T) getComponent(componentClass.getName(), name, false);
   }

   @SuppressWarnings("unchecked")
   public <T> T getComponent(String componentClassName, String name, boolean nameIsFQCN) {
      Component wrapper = lookupComponent(componentClassName, name, nameIsFQCN);
      if (wrapper == null) return null;

      return (T) unwrapComponent(wrapper);
   }

   /**
    * Get the component from a wrapper, properly handling <code>null</code> components.
    */
   private Object unwrapComponent(Component wrapper) {
      return wrapper.instance == NULL_COMPONENT ? null : wrapper.instance;
   }

   /**
    * Registers the default class loader.  This method *must* be called before any other components are registered,
    * typically called by bootstrap code.  Defensively, it is called in the constructor of ComponentRegistry with a null
    * parameter.
    *
    * @param loader a class loader to use by default.  If this is null, the class loader used to load this instance of
    *               ComponentRegistry is used.
    */
   protected ClassLoader registerDefaultClassLoader(ClassLoader loader) {
      ClassLoader loaderToUse = loader == null ? getClass().getClassLoader() : loader;
      registerComponent(loaderToUse, ClassLoader.class);
      // make sure the class loader is non-volatile, so it survives restarts.
//      componentLookup.get(ClassLoader.class.getName()).survivesRestarts = true;
      return loaderToUse;
   }

   /**
    * Rewires components.  Used to rewire components in the CR if a cache has been stopped (moved to state TERMINATED),
    * which would (almost) empty the registry of components.  Rewiring will re-inject all dependencies so that the cache
    * can be started again.
    * <p/>
    */
   public void rewire() {
      // need to re-inject everything again.
      for (Component c : new HashSet<Component>(componentLookup.values())) {
         // inject dependencies for this component
         c.injectDependencies();
      }
   }

   /**
    * Scans each registered component for lifecycle methods, and adds them to the appropriate lists, and then sorts them
    * by priority.
    */
   private void populateLifecycleMethods() {
      for (Component c : componentLookup.values()) populateLifeCycleMethods(c);
   }

   private PrioritizedMethod[] processPrioritizedMethods(ComponentMetadata.PrioritizedMethodMetadata[] methodMetadata,
                                                         Class<?> componentClass, Component c) {
      PrioritizedMethod[] retval;
      int numStartMethods = methodMetadata.length;
      if (numStartMethods == 0) {
         retval = EMPTY_PRIO_METHODS;
      } else {
         retval = new PrioritizedMethod[numStartMethods];
         for (int i = 0; i < numStartMethods; i++) {
            retval[i] = new PrioritizedMethod();
            retval[i].component = c;
            retval[i].metadata = methodMetadata[i];

            if (methodMetadata[i].getMethod() == null) {
               Method method = ReflectionUtil.findMethod(componentClass, methodMetadata[i].getMethodName());
               methodMetadata[i].setMethod(method);
            }
         }
         if (retval.length > 1) Arrays.sort(retval);
      }
      return retval;
   }


   private void populateLifeCycleMethods(Component c) {
      if (!c.methodsScanned) {
         c.methodsScanned = true;
         Class<?> componentClass = c.instance.getClass();

         // START methods first
         c.startMethods = processPrioritizedMethods(c.metadata.getStartMethods(), componentClass, c);

         // And now the STOP methods
         c.stopMethods = processPrioritizedMethods(c.metadata.getStopMethods(), componentClass, c);
      }
   }

   /**
    * Removes any components not annotated as @SurvivesRestarts.
    */
   public synchronized void resetVolatileComponents() {
      // destroy all components to clean up resources
      getLog().tracef("Resetting volatile components");
      for (Component c : new HashSet<Component>(componentLookup.values())) {
         // the component is volatile!!
         if (!c.metadata.isSurvivesRestarts()) {
            getLog().tracef("Removing volatile component %s", c.name);
            componentLookup.remove(c.name);
         }
      }

      if (getLog().isTraceEnabled())
         getLog().tracef("Reset volatile components. Registry now contains %s", componentLookup.keySet());
   }

   // ------------------------------ START: Publicly available lifecycle methods -----------------------------
   //   These methods perform a check for appropriate transition and then delegate to similarly named internal methods.

   /**
    * This starts the components in the cache, connecting to channels, starting service threads, etc.  If the cache is
    * not in the {@link org.infinispan.lifecycle.ComponentStatus#INITIALIZING} state, it will be initialized first.
    */
   @Override
   public synchronized void start() {

      if (!state.startAllowed()) {
         if (state.needToDestroyFailedCache())
            destroy(); // this will take us back to TERMINATED

         if (state.needToInitializeBeforeStart()) {
            rewire();
         } else
            return;
      }

      state = ComponentStatus.INITIALIZING;
      try {
         internalStart();
      } catch (Throwable t) {
         handleLifecycleTransitionFailure(t);
      }
   }

   /**
    * Stops the cache and sets the cache status to {@link org.infinispan.lifecycle.ComponentStatus#TERMINATED} once it
    * is done.  If the cache is not in the {@link org.infinispan.lifecycle.ComponentStatus#RUNNING} state, this is a
    * no-op.
    */
   @Override
   public synchronized void stop() {
      if (!state.stopAllowed()) {
         getLog().debugf("Ignoring call to stop() as current state is %s", this);
         return;
      }

      // Trying to stop() from FAILED is valid, but may not work
      boolean failed = state == ComponentStatus.FAILED;

      try {
         internalStop();
      } catch (Throwable t) {
         if (failed) {
            getLog().failedToCallStopAfterFailure(t);
         }
         failed = true;
         handleLifecycleTransitionFailure(t);
      } finally {
         if (!failed) state = ComponentStatus.TERMINATED;
      }
   }

   /**
    * Destroys the cache and frees up any resources.  Sets the cache status to {@link
    * org.infinispan.lifecycle.ComponentStatus#TERMINATED} when it is done.
    * <p/>
    * If the cache is in {@link org.infinispan.lifecycle.ComponentStatus#RUNNING} when this method is called, it will
    * first call {@link #stop()} to stop the cache.
    */
   private void destroy() {
      try {
         if (state.stopAllowed())
            stop();
      } catch (CacheException e) {
         getLog().stopBeforeDestroyFailed(e);
      }

      try {
         resetVolatileComponents();
      } finally {
         // We always progress to destroyed
         state = ComponentStatus.TERMINATED;
      }
   }
   // ------------------------------ END: Publicly available lifecycle methods -----------------------------

   // ------------------------------ START: Actual internal lifecycle methods --------------------------------

   /**
    * Sets the cacheStatus to FAILED and re-throws the problem as one of the declared types. Converts any
    * non-RuntimeException Exception to CacheException.
    *
    * @param t throwable thrown during failure
    */
   private void handleLifecycleTransitionFailure(Throwable t) {
      state = ComponentStatus.FAILED;
      if (t.getCause() != null && t.getCause() instanceof ConfigurationException)
         throw (ConfigurationException) t.getCause();
      else if (t.getCause() != null && t.getCause() instanceof InvocationTargetException && t.getCause().getCause() != null && t.getCause().getCause() instanceof ConfigurationException)
         throw (ConfigurationException) t.getCause().getCause();
      else if (t instanceof CacheException)
         throw (CacheException) t;
      else if (t instanceof RuntimeException)
         throw (RuntimeException) t;
      else if (t instanceof Error)
         throw (Error) t;
      else
         throw new CacheException(t);
   }

   private void internalStart() throws CacheException, IllegalArgumentException {
      // start all internal components
      // first cache all start, stop and destroy methods.
      populateLifecycleMethods();

      List<PrioritizedMethod> startMethods = new ArrayList<PrioritizedMethod>(componentLookup.size());
      for (Component c : componentLookup.values()) {
         Collections.addAll(startMethods, c.startMethods);
      }

      // sort the start methods by priority
      Collections.sort(startMethods);

      // fire all START methods according to priority

      invokeStartMethods(startMethods);
      addShutdownHook();

      state = ComponentStatus.RUNNING;
   }
   
   private void invokeStartMethods(Collection<PrioritizedMethod> startMethods) {
      boolean traceEnabled = getLog().isTraceEnabled();
      for (PrioritizedMethod em : startMethods) {
         if (traceEnabled)
            getLog().tracef("Invoking start method %s on component %s", em.metadata.getMethod(), em.component.getName());
         em.invoke();
      }
   }

   protected void addShutdownHook() {
      // no op.  Override if needed.
   }

   protected void removeShutdownHook() {
      // no op.  Override if needed.
   }

   /**
    * Actual stop
    */
   private void internalStop() {
      state = ComponentStatus.STOPPING;
      removeShutdownHook();

      List<PrioritizedMethod> stopMethods = new ArrayList<PrioritizedMethod>(componentLookup.size());
      for (Component c : componentLookup.values()) {
         // if one of the components threw an exception during startup
         // the stop methods list may not have been initialized
         if (c.stopMethods != null) {
            Collections.addAll(stopMethods, c.stopMethods);
         }
      }

      Collections.sort(stopMethods);

      // fire all STOP methods according to priority
      boolean traceEnabled = getLog().isTraceEnabled();
      for (PrioritizedMethod em : stopMethods) {
         if (traceEnabled)
            getLog().tracef("Invoking stop method %s on component %s", em.metadata.getMethod(), em.component.getName());
         try {
            em.invoke();
         } catch (Throwable t) {
            getLog().componentFailedToStop(t);
         }
      }

      destroy();
   }

   // ------------------------------ END: Actual internal lifecycle methods --------------------------------

   /**
    * Asserts whether invocations are allowed on the cache or not.  Returns <tt>true</tt> if invocations are to be
    * allowed, <tt>false</tt> otherwise.  If the origin of the call is remote and the cache status is {@link
    * org.infinispan.lifecycle.ComponentStatus#INITIALIZING}, this method will block for up to {@link
    * Configuration#getStateRetrievalTimeout()} millis, checking for a valid state.
    *
    * @param originLocal true if the call originates locally (i.e., from the {@link org.infinispan.CacheImpl} or false
    *                    if it originates remotely, i.e., from the {@link org.infinispan.remoting.InboundInvocationHandler}.
    * @return true if invocations are allowed, false otherwise.
    */
   public boolean invocationsAllowed(boolean originLocal) {
      getLog().trace("Testing if invocations are allowed.");
      if (state.allowInvocations()) return true;

      // if this is a locally originating call and the cache is not in a valid state, return false.
      if (originLocal) return false;

      getLog().trace("Is remotely originating.");

      // else if this is a remote call and the status is STARTING, wait until the cache starts.
      if (state == ComponentStatus.INITIALIZING) {
         getLog().trace("Cache is initializing; block.");
         try {
            blockUntilCacheStarts();
            return true;
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
         }
      } else {
         getLog().cacheNotStarted();
      }
      return false;
   }

   /**
    * Blocks until the current cache instance is in its {@link org.infinispan.lifecycle.ComponentStatus#RUNNING started}
    * phase. Blocks for up to {@link Configuration#getStateRetrievalTimeout()} milliseconds, throwing an
    * IllegalStateException if the cache doesn't reach this state even after this maximum wait time.
    *
    * @throws InterruptedException  if interrupted while waiting
    * @throws IllegalStateException if even after waiting the cache has not started.
    */
   private void blockUntilCacheStarts() throws InterruptedException, IllegalStateException {
      int pollFrequencyMS = 20;
      final long startupWaitTime = getConfiguration().getStateRetrievalTimeout();
      final long startupWaitTimeNanos = TimeUnit.NANOSECONDS.convert(startupWaitTime, TimeUnit.MILLISECONDS);
      final long giveUpTime = System.nanoTime() + startupWaitTimeNanos;

      while (System.nanoTime() < giveUpTime) {
         if (state.allowInvocations()) break;
         Thread.sleep(pollFrequencyMS);
      }

      // check if we have started.
      if (!state.allowInvocations())
         throw new IllegalStateException("Cache not in STARTED state, even after waiting " + getConfiguration().getStateRetrievalTimeout() + " millis.");
   }

   /**
    * Returns an immutable set containing all the components that exists in the repository at this moment.
    *
    * @return a set of components
    */
   public Set<Component> getRegisteredComponents() {
      HashSet<Component> defensiveCopy = new HashSet<Component>(componentLookup.values());
      return Collections.unmodifiableSet(defensiveCopy);
   }

   @Override
   public AbstractComponentRegistry clone() throws CloneNotSupportedException {
      AbstractComponentRegistry dolly = (AbstractComponentRegistry) super.clone();
      dolly.state = ComponentStatus.INSTANTIATED;
      return dolly;
   }

   /**
    * A wrapper representing a component in the registry
    */
   public class Component {

      /**
       * A reference to the object instance for this component.
       */
      Object instance;
      /**
       * The name of the component
       */
      String name;
      boolean methodsScanned;
      /**
       * List of injection methods used to inject dependencies into the component
       */
      ComponentMetadata.InjectMetadata[] injectionMethods;
      PrioritizedMethod[] startMethods;
      PrioritizedMethod[] stopMethods;
      ComponentMetadata metadata;

      @Override
      public String toString() {
         return "Component{" +
               "instance=" + instance +
               ", name=" + name +
               '}';
      }

      /**
       * Injects dependencies into this component.
       */
      public void injectDependencies() {
         if (injectionMethods != null && injectionMethods.length > 0) {
            for (ComponentMetadata.InjectMetadata injectMetadata : injectionMethods) invokeInjectionMethod(instance, injectMetadata);
         }
      }

      public Object getInstance() {
         return instance;
      }

      public String getName() {
         return name;
      }

      public ComponentMetadata getMetadata() {
         return metadata;
      }

      public void buildInjectionMethodsList() throws ClassNotFoundException {
         injectionMethods = metadata.getInjectMethods();
         if (injectionMethods != null && injectionMethods.length > 0) {
            Class<?> clazz = instance.getClass();
            for (ComponentMetadata.InjectMetadata meta: injectionMethods) {
               Class<?>[] parameterClasses = meta.getParameterClasses();
               if (parameterClasses == null) {
                  parameterClasses = ReflectionUtil.toClassArray(meta.getParameters());
                  meta.setParameterClasses(parameterClasses);
               }
               Method m = meta.getMethod();
               if (m == null) {
                  m = ReflectionUtil.findMethod(clazz, meta.getMethodName(), parameterClasses);
                  meta.setMethod(m);
               }
            }
         }
      }
   }

   /**
    * Wrapper to encapsulate a method along with a priority
    */
   static class PrioritizedMethod implements Comparable<PrioritizedMethod> {
      ComponentMetadata.PrioritizedMethodMetadata metadata;
      Component component;

      @Override
      public int compareTo(PrioritizedMethod o) {
         int thisVal = metadata.getPriority();
         int anotherVal = o.metadata.getPriority();
         return (thisVal < anotherVal ? -1 : (thisVal == anotherVal ? 0 : 1));
      }


      @Override
      public boolean equals(Object o) {
         if (this == o) return true;
         if (!(o instanceof PrioritizedMethod)) return false;

         PrioritizedMethod that = (PrioritizedMethod) o;

         if (component != null ? !component.equals(that.component) : that.component != null) return false;
         if (metadata != null ? !metadata.equals(that.metadata) : that.metadata != null) return false;

         return true;
      }

      @Override
      public int hashCode() {
         int result = metadata != null ? metadata.hashCode() : 0;
         result = 31 * result + (component != null ? component.hashCode() : 0);
         return result;
      }

      void invoke() {
         ReflectionUtil.invokeAccessibly(component.instance, metadata.getMethod(), null);
      }

      @Override
      public String toString() {
         return "PrioritizedMethod{" +
               "method=" + metadata.getMethod().getName() +
               ", priority=" + metadata.getPriority() +
               '}';
      }

   }

   private void throwStackAwareConfigurationException(String message) {
      if (debugStack == null) {
         throw new ConfigurationException(message + ". To get more detail set the system property " + DEPENDENCIES_ENABLE_JVMOPTION + " to true");
      } else {
         throw new ConfigurationException(message + " Debug stack: " + debugStack);
      }
   }

}
