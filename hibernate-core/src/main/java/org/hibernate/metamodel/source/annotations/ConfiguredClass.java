/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2011, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.metamodel.source.annotations;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.persistence.AccessType;

import com.fasterxml.classmate.ResolvedTypeWithMembers;
import com.fasterxml.classmate.members.HierarchicType;
import com.fasterxml.classmate.members.ResolvedMember;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.MethodInfo;

import org.hibernate.AnnotationException;
import org.hibernate.AssertionFailure;
import org.hibernate.HibernateException;
import org.hibernate.metamodel.source.annotations.util.JandexHelper;
import org.hibernate.metamodel.source.annotations.util.ReflectionHelper;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.service.classloading.spi.ClassLoaderService;

/**
 * Represents an entity, mapped superclass or component configured via annotations/xml.
 *
 * @author Hardy Ferentschik
 */
public class ConfiguredClass {
	private final ConfiguredClass parent;
	private final ClassInfo classInfo;
	private final Class<?> clazz;
	private final boolean isRoot;
	private final AccessType classAccessType;
	private final AccessType hierarchyAccessType;
	private final boolean isMappedSuperClass;
	private final Map<String, MappedProperty> mappedProperties;

	public ConfiguredClass(ClassInfo info, ConfiguredClass parent, AccessType hierarchyAccessType, ServiceRegistry serviceRegistry, ResolvedTypeWithMembers resolvedType) {
		this.classInfo = info;
		this.parent = parent;
		this.isRoot = parent == null;
		this.hierarchyAccessType = hierarchyAccessType;

		AnnotationInstance mappedSuperClassAnnotation = assertNotEntityAndMappedSuperClass();

		this.clazz = serviceRegistry.getService( ClassLoaderService.class ).classForName( info.toString() );
		isMappedSuperClass = mappedSuperClassAnnotation != null;
		classAccessType = determineClassAccessType();

		List<MappedProperty> properties = collectMappedProperties( resolvedType );
		// make sure the properties are ordered by property name
		Collections.sort( properties );
		Map<String, MappedProperty> tmpMap = new LinkedHashMap<String, MappedProperty>();
		for ( MappedProperty property : properties ) {
			tmpMap.put( property.getName(), property );
		}
		mappedProperties = Collections.unmodifiableMap( tmpMap );
	}

	public String getName() {
		return clazz.getName();
	}

	public ClassInfo getClassInfo() {
		return classInfo;
	}

	public ConfiguredClass getParent() {
		return parent;
	}

	public boolean isRoot() {
		return isRoot;
	}

	public boolean isMappedSuperClass() {
		return isMappedSuperClass;
	}

	public Iterable<MappedProperty> getMappedProperties() {
		return mappedProperties.values();
	}

	public MappedProperty getMappedProperty(String propertyName) {
		return mappedProperties.get( propertyName );
	}

	@Override
	public String toString() {
		final StringBuilder sb = new StringBuilder();
		sb.append( "ConfiguredClass" );
		sb.append( "{clazz=" ).append( clazz );
		sb.append( ", mappedProperties=" ).append( mappedProperties );
		sb.append( ", classAccessType=" ).append( classAccessType );
		sb.append( ", isRoot=" ).append( isRoot );
		sb.append( '}' );
		return sb.toString();
	}

	private AccessType determineClassAccessType() {
		// default to the hierarchy access type to start with
		AccessType accessType = hierarchyAccessType;

		AnnotationInstance accessAnnotation = JandexHelper.getSingleAnnotation( classInfo, JPADotNames.ACCESS );
		if ( accessAnnotation != null ) {
			accessType = Enum.valueOf( AccessType.class, accessAnnotation.value( "value" ).asEnum() );
		}

		return accessType;
	}

	/**
	 * @return A list of the persistent properties of this configured class
	 */
	private List<MappedProperty> collectMappedProperties(ResolvedTypeWithMembers resolvedTypes) {
		// create sets of transient field and method names
		Set<String> transientFieldNames = new HashSet<String>();
		Set<String> transientMethodNames = new HashSet<String>();
		populateTransientFieldAndMethodLists( transientFieldNames, transientMethodNames );

		// use the class mate library to generic types
		ResolvedTypeWithMembers resolvedType = null;
		for ( HierarchicType hierarchicType : resolvedTypes.allTypesAndOverrides() ) {
			if ( hierarchicType.getType().getErasedType().equals( clazz ) ) {
				resolvedType = ReflectionHelper.resolveMemberTypes( hierarchicType.getType() );
				break;
			}
		}

		if ( resolvedType == null ) {
			throw new AssertionFailure( "Unable to resolve types for " + clazz.getName() );
		}

		List<MappedProperty> properties = new ArrayList<MappedProperty>();
		Set<String> explicitlyConfiguredMemberNames = createExplicitlyConfiguredAccessProperties(
				properties, resolvedType
		);

		if ( AccessType.FIELD.equals( classAccessType ) ) {
			Field fields[] = clazz.getDeclaredFields();
			Field.setAccessible( fields, true );
			for ( Field field : fields ) {
				if ( isPersistentMember( transientFieldNames, explicitlyConfiguredMemberNames, field ) ) {
					properties.add( createMappedProperty( field, resolvedType ) );
				}
			}
		}
		else {
			Method[] methods = clazz.getDeclaredMethods();
			Method.setAccessible( methods, true );
			for ( Method method : methods ) {
				if ( isPersistentMember( transientMethodNames, explicitlyConfiguredMemberNames, method ) ) {
					properties.add( createMappedProperty( method, resolvedType ) );
				}
			}
		}
		return properties;
	}

	private boolean isPersistentMember(Set<String> transientNames, Set<String> explicitlyConfiguredMemberNames, Member member) {
		if ( !ReflectionHelper.isProperty( member ) ) {
			return false;
		}

		if ( transientNames.contains( member.getName() ) ) {
			return false;
		}

		if ( explicitlyConfiguredMemberNames.contains( member.getName() ) ) {
			return false;
		}

		return true;
	}

	/**
	 * Creates {@code MappedProperty} instances for the explicitly configured persistent properties
	 *
	 * @param mappedProperties list to which to add the explicitly configured mapped properties
	 *
	 * @return the property names of the explicitly configured class names in a set
	 */
	private Set<String> createExplicitlyConfiguredAccessProperties(List<MappedProperty> mappedProperties, ResolvedTypeWithMembers resolvedMembers) {
		Set<String> explicitAccessMembers = new HashSet<String>();

		List<AnnotationInstance> accessAnnotations = classInfo.annotations().get( JPADotNames.ACCESS );
		if ( accessAnnotations == null ) {
			return explicitAccessMembers;
		}

		// iterate over all @Access annotations defined on the current class
		for ( AnnotationInstance accessAnnotation : accessAnnotations ) {
			// we are only interested at annotations defined on fields and methods
			AnnotationTarget annotationTarget = accessAnnotation.target();
			if ( !( annotationTarget.getClass().equals( MethodInfo.class ) || annotationTarget.getClass()
					.equals( FieldInfo.class ) ) ) {
				continue;
			}

			AccessType accessType = Enum.valueOf( AccessType.class, accessAnnotation.value().asEnum() );

			// when class access type is field
			// overriding access annotations must be placed on properties and have the access type PROPERTY
			if ( AccessType.FIELD.equals( classAccessType ) ) {
				if ( !( annotationTarget instanceof MethodInfo ) ) {
					// todo log warning !?
					continue;
				}

				if ( !AccessType.PROPERTY.equals( accessType ) ) {
					// todo log warning !?
					continue;
				}
			}

			// when class access type is property
			// overriding access annotations must be placed on fields and have the access type FIELD
			if ( AccessType.PROPERTY.equals( classAccessType ) ) {
				if ( !( annotationTarget instanceof FieldInfo ) ) {
					// todo log warning !?
					continue;
				}

				if ( !AccessType.FIELD.equals( accessType ) ) {
					// todo log warning !?
					continue;
				}
			}

			// the placement is correct, get the member
			Member member;
			if ( annotationTarget instanceof MethodInfo ) {
				Method m;
				try {
					m = clazz.getMethod( ( (MethodInfo) annotationTarget ).name() );
				}
				catch ( NoSuchMethodException e ) {
					throw new HibernateException(
							"Unable to load method "
									+ ( (MethodInfo) annotationTarget ).name()
									+ " of class " + clazz.getName()
					);
				}
				member = m;
			}
			else {
				Field f;
				try {
					f = clazz.getField( ( (FieldInfo) annotationTarget ).name() );
				}
				catch ( NoSuchFieldException e ) {
					throw new HibernateException(
							"Unable to load field "
									+ ( (FieldInfo) annotationTarget ).name()
									+ " of class " + clazz.getName()
					);
				}
				member = f;
			}
			if ( ReflectionHelper.isProperty( member ) ) {
				mappedProperties.add( createMappedProperty( member, resolvedMembers ) );
				explicitAccessMembers.add( member.getName() );
			}
		}
		return explicitAccessMembers;
	}

	private MappedProperty createMappedProperty(Member member, ResolvedTypeWithMembers resolvedType) {
		String name = ReflectionHelper.getPropertyName( member );
		ResolvedMember[] resolvedMembers;
		if ( member instanceof Field ) {
			resolvedMembers = resolvedType.getMemberFields();
		}
		else {
			resolvedMembers = resolvedType.getMemberMethods();
		}
		Type type = findResolvedType( member.getName(), resolvedMembers );
		return new MappedProperty( name, (Class) type );
	}

	private Type findResolvedType(String name, ResolvedMember[] resolvedMembers) {
		for ( ResolvedMember resolvedMember : resolvedMembers ) {
			if ( resolvedMember.getName().equals( name ) ) {
				return resolvedMember.getType().getErasedType();
			}
		}
		// todo - what to do here
		return null;
	}

	/**
	 * Populates the sets of transient field and method names.
	 *
	 * @param transientFieldNames Set to populate with the field names explicitly marked as @Transient
	 * @param transientMethodNames set to populate with the method names explicitly marked as @Transient
	 */
	private void populateTransientFieldAndMethodLists(Set<String> transientFieldNames, Set<String> transientMethodNames) {
		List<AnnotationInstance> transientMembers = classInfo.annotations().get( JPADotNames.TRANSIENT );
		if ( transientMembers == null ) {
			return;
		}

		for ( AnnotationInstance transientMember : transientMembers ) {
			AnnotationTarget target = transientMember.target();
			if ( target instanceof FieldInfo ) {
				transientFieldNames.add( ( (FieldInfo) target ).name() );
			}
			else {
				transientMethodNames.add( ( (MethodInfo) target ).name() );
			}
		}
	}

	private AnnotationInstance assertNotEntityAndMappedSuperClass() {
		//@Entity and @MappedSuperclass on the same class leads to a NPE down the road
		AnnotationInstance jpaEntityAnnotation = JandexHelper.getSingleAnnotation( classInfo, JPADotNames.ENTITY );
		AnnotationInstance mappedSuperClassAnnotation = JandexHelper.getSingleAnnotation(
				classInfo, JPADotNames.MAPPED_SUPER_CLASS
		);

		if ( jpaEntityAnnotation != null && mappedSuperClassAnnotation != null ) {
			throw new AnnotationException(
					"An entity cannot be annotated with both @Entity and @MappedSuperclass: "
							+ classInfo.name().toString()
			);
		}
		return mappedSuperClassAnnotation;
	}
}


