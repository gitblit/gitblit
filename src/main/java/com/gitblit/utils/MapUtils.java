package com.gitblit.utils;

import java.text.MessageFormat;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.apache.mina.util.ConcurrentHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A utility wrapper class to generate a default value over an existing map
 * 
 * @author Jan Vanhercke
 *
 */
public class MapUtils {

	private static final Logger logger = LoggerFactory.getLogger(MapUtils.class);

	public static <K, V> Map<K, V> defaultMap(Map<K, V> delegate, V value) {
		return new Wrap<>(delegate, value);
	}

	private static class Wrap<K, V> implements Map<K, V> {

		private Map<K, V> delegate;

		// HashSet is only used to reduce logging

		private Set<K> unknownKeys = new ConcurrentHashSet<>();

		private V value;

		private Wrap(Map<K, V> delegate, V value) {
			this.delegate = delegate;
			this.value = value;
		}

		@Override
		public int size() {
			return delegate.size();
		}

		@Override
		public boolean isEmpty() {
			return delegate.isEmpty();
		}

		@Override
		public boolean containsKey(Object key) {
			return true;
		}

		@Override
		public boolean containsValue(Object value) {
			return true;
		}

		@SuppressWarnings("unchecked")
		@Override
		public V get(Object key) {
			V retv = delegate.get(key);

			if (retv == null) {
				if (unknownKeys.add((K) key))
					logger.error(MessageFormat.format("Default value {0} generated for key {1}", value, key));

				return value;
			}

			return retv;
		}

		@Override
		public V put(K key, V value) {
			return delegate.put(key, value);
		}

		@Override
		public V remove(Object key) {
			V retv = delegate.remove(key);

			if (retv == null)
				return value;

			return value;
		}

		@Override
		public void putAll(Map<? extends K, ? extends V> m) {
			delegate.putAll(m);
		}

		@Override
		public void clear() {
			delegate.clear();
		}

		@Override
		public Set<K> keySet() {
			return delegate.keySet();
		}

		@Override
		public Collection<V> values() {
			return delegate.values();
		}

		@Override
		public Set<java.util.Map.Entry<K, V>> entrySet() {
			return delegate.entrySet();
		}
	}
}
