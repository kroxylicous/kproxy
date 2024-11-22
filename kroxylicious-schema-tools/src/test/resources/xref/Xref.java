/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package xref;

/**
 * A class with scalar properties
 */
@javax.annotation.processing.Generated("io.kroxylicious.tools.schema.CodeGen")
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
@com.fasterxml.jackson.annotation.JsonPropertyOrder({ "myBoolean", "myList", "myObject" })
@com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = com.fasterxml.jackson.databind.JsonDeserializer.None.class)
public class Xref {

    @com.fasterxml.jackson.annotation.JsonProperty("myBoolean")
    @com.fasterxml.jackson.annotation.JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.SKIP)
    private java.lang.Boolean myBoolean;

    @com.fasterxml.jackson.annotation.JsonProperty("myList")
    @com.fasterxml.jackson.annotation.JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.SKIP)
    private java.util.List<java.lang.Long> myList;

    @com.fasterxml.jackson.annotation.JsonProperty("myObject")
    @com.fasterxml.jackson.annotation.JsonSetter(nulls = com.fasterxml.jackson.annotation.Nulls.SKIP)
    private xref.MyObject myObject;

    /**
     * A class with scalar properties
     */
    public java.lang.Boolean getMyBoolean() {
        return this.myBoolean;
    }

    /**
     * A class with scalar properties
     */
    public void setMyBoolean(java.lang.Boolean myBoolean) {
        this.myBoolean = myBoolean;
    }

    /**
     * A class with scalar properties
     */
    public java.util.List<java.lang.Long> getMyList() {
        return this.myList;
    }

    /**
     * A class with scalar properties
     */
    public void setMyList(java.util.List<java.lang.Long> myList) {
        this.myList = myList;
    }

    /**
     * A class with scalar properties
     */
    public xref.MyObject getMyObject() {
        return this.myObject;
    }

    /**
     * A class with scalar properties
     */
    public void setMyObject(xref.MyObject myObject) {
        this.myObject = myObject;
    }

    @java.lang.Override()
    public java.lang.String toString() {
        return "Xref[" + "myBoolean: " + this.myBoolean + ", myList: " + this.myList + ", myObject: " + this.myObject + "]";
    }

    @java.lang.Override()
    public int hashCode() {
        return java.util.Objects.hash(this.myBoolean, this.myList, this.myObject);
    }

    @java.lang.Override()
    public boolean equals(java.lang.Object other) {
        if (this == other)
            return true;
        else if (other instanceof xref.Xref otherXref)
            return java.util.Objects.equals(this.myBoolean, otherXref.myBoolean) && java.util.Objects.equals(this.myList, otherXref.myList) && java.util.Objects.equals(this.myObject, otherXref.myObject);
        else
            return false;
    }
}