/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package arrays;

/**
 * An class with properties mapped from the array type.
 */
@javax.annotation.processing.Generated("io.kroxylicious.tools.schema.compiler.CodeGen")
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
@com.fasterxml.jackson.annotation.JsonPropertyOrder({ "strings", "stringSet", "integers", "objects", "fooBars" })
@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.fasterxml.jackson.databind.JsonDeserializer.None.class)
public class Arrays {

    @com.fasterxml.jackson.annotation.JsonProperty(value = "strings")
    @com.fasterxml.jackson.annotation.JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.SKIP)
    private java.util.List<java.lang.String> strings;

    @com.fasterxml.jackson.annotation.JsonProperty(value = "stringSet")
    @com.fasterxml.jackson.annotation.JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.SKIP)
    private java.util.Set<java.lang.String> stringSet;

    @com.fasterxml.jackson.annotation.JsonProperty(value = "integers")
    @com.fasterxml.jackson.annotation.JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.SKIP)
    private java.util.List<java.lang.Long> integers;

    @com.fasterxml.jackson.annotation.JsonProperty(value = "objects")
    @com.fasterxml.jackson.annotation.JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.SKIP)
    private java.util.List<arrays.ArraysObject> objects;

    @com.fasterxml.jackson.annotation.JsonProperty(value = "fooBars")
    @com.fasterxml.jackson.annotation.JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.SKIP)
    private java.util.List<arrays.FooBar> fooBars;

    /**
     * An array of strings
     * @return The value of this object's strings.
     */
    public java.util.List<java.lang.String> getStrings() {
        return this.strings;
    }

    /**
     * An array of strings
     *  @param strings The new value for this object's strings.
     */
    public void setStrings(java.util.List<java.lang.String> strings) {
        this.strings = strings;
    }

    /**
     * An array of strings
     * @return The value of this object's stringSet.
     */
    public java.util.Set<java.lang.String> getStringSet() {
        return this.stringSet;
    }

    /**
     * An array of strings
     *  @param stringSet The new value for this object's stringSet.
     */
    public void setStringSet(java.util.Set<java.lang.String> stringSet) {
        this.stringSet = stringSet;
    }

    /**
     * An array of integers
     * @return The value of this object's integers.
     */
    public java.util.List<java.lang.Long> getIntegers() {
        return this.integers;
    }

    /**
     * An array of integers
     *  @param integers The new value for this object's integers.
     */
    public void setIntegers(java.util.List<java.lang.Long> integers) {
        this.integers = integers;
    }

    /**
     * An array of objects (not via $ref)
     * @return The value of this object's objects.
     */
    public java.util.List<arrays.ArraysObject> getObjects() {
        return this.objects;
    }

    /**
     * An array of objects (not via $ref)
     *  @param objects The new value for this object's objects.
     */
    public void setObjects(java.util.List<arrays.ArraysObject> objects) {
        this.objects = objects;
    }

    /**
     * An array of FooBars
     * @return The value of this object's fooBars.
     */
    public java.util.List<arrays.FooBar> getFooBars() {
        return this.fooBars;
    }

    /**
     * An array of FooBars
     *  @param fooBars The new value for this object's fooBars.
     */
    public void setFooBars(java.util.List<arrays.FooBar> fooBars) {
        this.fooBars = fooBars;
    }

    @java.lang.Override()
    public java.lang.String toString() {
        return "Arrays[" + "strings: " + this.strings + ", stringSet: " + this.stringSet + ", integers: " + this.integers + ", objects: " + this.objects + ", fooBars: " + this.fooBars + "]";
    }

    @java.lang.Override()
    public int hashCode() {
        return java.util.Objects.hash(this.strings, this.stringSet, this.integers, this.objects, this.fooBars);
    }

    @java.lang.Override()
    public boolean equals(java.lang.Object other) {
        if (this == other)
            return true;
        else if (other instanceof arrays.Arrays otherArrays)
            return java.util.Objects.equals(this.strings, otherArrays.strings) && java.util.Objects.equals(this.stringSet, otherArrays.stringSet) && java.util.Objects.equals(this.integers, otherArrays.integers) && java.util.Objects.equals(this.objects, otherArrays.objects) && java.util.Objects.equals(this.fooBars, otherArrays.fooBars);
        else
            return false;
    }
}