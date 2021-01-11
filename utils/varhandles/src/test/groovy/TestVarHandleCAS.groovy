import datadog.trace.core.varhandles.VarHandleCASFactory
import datadog.trace.test.util.DDSpecification
import datadog.trace.unsafe.IntCAS
import datadog.trace.unsafe.ReferenceCAS

class TestVarHandleCAS extends DDSpecification {

  def "test varhandle CAS"() {
    setup:
    ObjectToCAS testObject = new ObjectToCAS()
    def casFactory = new VarHandleCASFactory()

    when:
    testObject.stringField = "foo"
    ReferenceCAS<String> casString = casFactory.createReferenceCAS(ObjectToCAS, "stringField", String)

    then:
    casString.compareAndSet(testObject, "foo", "bar")
    !casString.compareAndSet(testObject, "foo", "bar")


    when:
    testObject.intField = 42
    IntCAS casInt = casFactory.createIntCAS(ObjectToCAS, "intField")

    then:
    casInt.compareAndSet(testObject, 42, 43)
    !casInt.compareAndSet(testObject, 42, 43)
  }

  def "field type mismatches are rejected"() {
    setup:
    def casFactory = new VarHandleCASFactory()
    when:
    casFactory.createReferenceCAS(ObjectToCAS, "stringField", List)
    then:
    thrown IllegalArgumentException
  }

  def "missing field are rejected"() {
    setup:
    def casFactory = new VarHandleCASFactory()
    when:
    casFactory.createReferenceCAS(ObjectToCAS, "missing", String)
    then:
    thrown IllegalArgumentException
  }
}
