---
paths: ["**/Plain.java"]
---

<!-- VIBETAGS-START -->
# Rules for Plain

## Locked Status
- **Reason**: plain class

### Rules for method gen
- **Reason**: generic

### Rules for field CCONST
- **Reason**: companion const

### Rules for constructor <init>
- **Reason**: secondary ctor

### Rules for method alias
- **Reason**: typealias

### Rules for method anys
- **Reason**: any nothing

### Rules for method arrays
- **Reason**: arrays

### Rules for field cjf
- **Reason**: companion jvmfield

### Rules for method colls
- **Reason**: colls

### Rules for field counter
- **Reason**: field var

### Rules for method create
- **Reason**: jvmstatic

### Rules for field cval
- **Reason**: companion val

### Rules for method defs
- **Reason**: overloads

### Rules for method defs
- **Reason**: overloads

### Rules for method defs
- **Reason**: overloads

### Rules for method fns
- **Reason**: fn types

### Rules for field ft
- **Reason**: field target

### Rules for method getComputed
- **Reason**: getter

### Rules for method hidden
- **Reason**: private fun

### Rules for method inside$kapt_gt
- **Reason**: internal

### Rules for field jf
- **Reason**: jvmfield

### Rules for method lam
- **Reason**: unit returning lambda

### Rules for field late
- **Reason**: lateinit

### Rules for method ng
- **Reason**: nested generic

### Rules for method over
- **Reason**: overload a

### Rules for method over
- **Reason**: overload b

### Rules for method paramAnn
- **Reason**: param ann

### Rules for method plain
- **Reason**: plain fun

### Rules for method plus
- **Reason**: operator

### Rules for method prims
- **Reason**: prims

### Rules for method renamed
- **Reason**: jvmname

### Rules for method res
- **Reason**: result

### Rules for method ret
- **Reason**: returns list

### Rules for field secret
- **Reason**: private prop

### Rules for method setSettable
- **Reason**: setter

### Rules for method sus
- **Reason**: suspend

### Rules for field tag
- **Reason**: ctor val field-only

### Rules for method va
- **Reason**: vararg

### Rules for method with
- **Reason**: infix

## Input Sanitization
- **Target Filters**: SQL_INJECTION
- **Rule**: Run raw input strings through approved sanitizers.
- **Applies to**: `Plain.Plain(long,java.lang.String,java.lang.String)#name`, `Plain.paramAnn(java.lang.String,int)#second`

## Load-Bearing Oddity
- **Rule**: This looks removable but is deliberate. Refactor only while the invariant holds.

### Rules for parameter Plain.Plain(long,java.lang.String,java.lang.String)#id
- **Invariant**: ctor val param

### Rules for field id
- **Invariant**: ctor val param

### Rules for parameter Plain.paramAnn(java.lang.String,int)#first
- **Invariant**: p1

### Rules for parameter Plain.setSp(java.lang.String)#p0
- **Invariant**: setparam
<!-- VIBETAGS-END -->
