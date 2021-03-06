package org.infinispan.marshall.exts;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.infinispan.commons.marshall.AbstractExternalizer;
import org.infinispan.commons.util.Util;
import org.infinispan.marshall.core.Ids;

import net.jcip.annotations.Immutable;

/**
 * SingletonListExternalizer.
 *
 * @author Galder Zamarreño
 * @since 4.0
 */
@Immutable
public class SingletonListExternalizer extends AbstractExternalizer<List<?>> {

   @Override
   public void writeObject(ObjectOutput output, List<?> list) throws IOException {
      output.writeObject(list.get(0));
   }

   @Override
   public List<?> readObject(ObjectInput input) throws IOException, ClassNotFoundException {
      return Collections.singletonList(input.readObject());
   }

   @Override
   public Integer getId() {
      return Ids.SINGLETON_LIST;
   }

   @Override
   public Set<Class<? extends List<?>>> getTypeClasses() {
      // This is loadable from any classloader
      return Util.<Class<? extends List<?>>>asSet(Util.<List<?>>loadClass("java.util.Collections$SingletonList", null));
   }

}
