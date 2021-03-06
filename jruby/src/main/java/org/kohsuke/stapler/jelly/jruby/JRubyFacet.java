package org.kohsuke.stapler.jelly.jruby;

import org.apache.commons.jelly.JellyTagException;
import org.apache.commons.jelly.Script;
import org.jruby.RubyClass;
import org.jruby.RubyObject;
import org.jruby.embed.LocalContextScope;
import org.jruby.embed.ScriptingContainer;
import org.kohsuke.MetaInfServices;
import org.kohsuke.stapler.Dispatcher;
import org.kohsuke.stapler.Facet;
import org.kohsuke.stapler.MetaClass;
import org.kohsuke.stapler.RequestImpl;
import org.kohsuke.stapler.ResponseImpl;
import org.kohsuke.stapler.TearOffSupport;
import org.kohsuke.stapler.WebApp;
import org.kohsuke.stapler.jelly.JellyCompatibleFacet;
import org.kohsuke.stapler.jelly.JellyFacet;
import org.kohsuke.stapler.jelly.jruby.erb.ERbClassTearOff;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

/**
 * {@link Facet} that adds Ruby-based view technologies.
 *
 * @author Kohsuke Kawaguchi
 * @author Hiroshi Nakamura
 */
@MetaInfServices(Facet.class)
public class JRubyFacet extends Facet implements JellyCompatibleFacet {
    private final Map<RubyClass,JRubyClassInfo> classMap = new WeakHashMap<RubyClass,JRubyClassInfo>();

    /*package*/ final List<RubyTemplateLanguage> languages = new CopyOnWriteArrayList<RubyTemplateLanguage>();

    /**
     * There are all kinds of downsides in doing this, but for the time being we just use one scripting container.
     */
    private final ScriptingContainer container;

    /**
     * {@link RubyTemplateContainer}s keyed by their {@linkplain RubyTemplateLanguage#getScriptExtension() extensions}.
     * (since {@link #container} is a singleton per {@link JRubyFacet}, this is also just one map.
     */
    private final Map<String,RubyTemplateContainer> templateContainers = new HashMap<String, RubyTemplateContainer>();

    private final Collection<Class<? extends AbstractRubyTearOff>> tearOffTypes = new CopyOnWriteArrayList<Class<? extends AbstractRubyTearOff>>();

    public JRubyFacet() {
        // TODO: is this too early? Shall we allow registrations later?
        languages.addAll(Facet.discoverExtensions(RubyTemplateLanguage.class,
                Thread.currentThread().getContextClassLoader(), getClass().getClassLoader()));

        container = new ScriptingContainer(LocalContextScope.SINGLETHREAD); // we don't want any funny multiplexing from ScriptingContainer.
        container.runScriptlet("require 'org/kohsuke/stapler/jelly/jruby/JRubyJellyScriptImpl'");

        for (RubyTemplateLanguage l : languages) {
            templateContainers.put(l.getScriptExtension(),l.createContainer(container));
            tearOffTypes.add(l.getTearOffClass());
        }
    }

    private RubyTemplateContainer selectTemplateContainer(String path) {
        int idx = path.lastIndexOf('.');
        if (idx >= 0) {
            RubyTemplateContainer t = templateContainers.get(path.substring(idx));
            if (t!=null)    return t;
        }
        throw new IllegalArgumentException("Unrecognized file extension: "+path);
    }

    public Script parseScript(URL template) throws IOException {
        return selectTemplateContainer(template.getPath()).parseScript(template);
    }

    public synchronized JRubyClassInfo getClassInfo(RubyClass r) {
        if (r==null)    return null;

        JRubyClassInfo o = classMap.get(r);
        if (o==null)
            classMap.put(r,o=new JRubyClassInfo(this,r));
        return o;
    }

    public void buildViewDispatchers(final MetaClass owner, List<Dispatcher> dispatchers) {
        if (RubyObject.class.isAssignableFrom(owner.clazz)) {
            dispatchers.add(new ScriptInvokingDispatcher() {
                @Override
                public boolean dispatch(RequestImpl req, ResponseImpl rsp, Object node) throws IOException, ServletException, IllegalAccessException, InvocationTargetException {
                    String next = req.tokens.peek();
                    if(next==null) return false;
                    JRubyClassInfo info = getClassInfo(((RubyObject) node).getMetaClass());
                    return invokeScript(req, rsp, node, next, info.findScript(next));
                }
            });
        }
        for (final Class<? extends AbstractRubyTearOff> t : getClassTearOffTypes()) {
            dispatchers.add(new ScriptInvokingDispatcher() {
                final AbstractRubyTearOff tearOff = owner.loadTearOff(t);
                @Override
                public boolean dispatch(RequestImpl req, ResponseImpl rsp, Object node) throws IOException, ServletException {
                    String next = req.tokens.peek();
                    if(next==null) return false;
                    return invokeScript(req, rsp, node, next, tearOff.findScript(next));
                }
            });
        }
    }

    protected abstract class ScriptInvokingDispatcher extends Dispatcher {
        protected boolean invokeScript(RequestImpl req, ResponseImpl rsp, Object node, String next, Script script) throws IOException, ServletException {
            try {
                if(script==null) return false;
                
                req.tokens.next();

                if(traceable())
                    trace(req,rsp,"Invoking "+next+" on "+node+" for "+req.tokens);

                WebApp.getCurrent().getFacet(JellyFacet.class).scriptInvoker.invokeScript(req, rsp, script, node);

                return true;
            } catch (RuntimeException e) {
                throw e;
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new ServletException(e);
            }
        }

        public String toString() {
            return "TOKEN for url=/TOKEN/...";
        }
    }

    public Collection<Class<? extends AbstractRubyTearOff>> getClassTearOffTypes() {
        return tearOffTypes;
    }

    public Collection<String> getScriptExtensions() {
        List<String> r = new ArrayList<String>();
        for (RubyTemplateLanguage l : languages)
            r.add(l.getScriptExtension());
        return r;
    }


    public RequestDispatcher createRequestDispatcher(RequestImpl request, Class type, Object it, String viewName) throws IOException {
        TearOffSupport mc = request.stapler.getWebApp().getMetaClass(type);
        return mc.loadTearOff(ERbClassTearOff.class).createDispatcher(it,viewName);
    }

    public boolean handleIndexRequest(RequestImpl req, ResponseImpl rsp, Object node, MetaClass nodeMetaClass) throws IOException, ServletException {
        if (node instanceof RubyObject) {
            JRubyClassInfo info = getClassInfo(((RubyObject) node).getMetaClass());
            Script script = info.findScript("index");
            if (script!=null) {
                try {
                    WebApp.getCurrent().getFacet(JellyFacet.class).scriptInvoker.invokeScript(req, rsp, script, node);
                    return true;
                } catch (JellyTagException e) {
                    throw new ServletException(e);
                }
            }
        }

        for (Class<? extends AbstractRubyTearOff> t : getClassTearOffTypes()) {
            AbstractRubyTearOff rt = nodeMetaClass.loadTearOff(t);
            Script script = rt.findScript("index");
            if(script!=null) {
                try {
                    if(LOGGER.isLoggable(Level.FINE))
                        LOGGER.fine("Invoking index"+rt.getDefaultScriptExtension()+" on " + node);
                    WebApp.getCurrent().getFacet(JellyFacet.class).scriptInvoker.invokeScript(req, rsp, script, node);
                    return true;
                } catch (JellyTagException e) {
                    throw new ServletException(e);
                }
            }
        }

        return false;
    }
}

