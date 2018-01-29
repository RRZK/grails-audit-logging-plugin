/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
*/
package test

import grails.testing.mixin.integration.Integration
import grails.transaction.*
import spock.lang.*

@Integration
@Rollback
class AuditUpdateSpec extends Specification {

    void setupData() {
        def author = new Author(name: "Aaron", age: 37, famous: true)
        author.addToBooks(new Book(title: 'Hunger Games', description: 'Blah', pages: 400))
        author.addToBooks(new Book(title: 'Catching Fire', description: 'Blah', pages: 500))
        author.save(flush: true, failOnError: true)

        def publisher = new Publisher(code: 'ABC123', name: 'Random House', active: true)
        publisher.save(flush: true, failOnError: true)

        def heliport = new Heliport(code: 'EGLW', name: 'Battersea Heliport')
        heliport.save(flush: true, failOnError: true)

        // Remove all logging of the inserts, we are focused on updates here
        AuditTrail.withNewSession {
            AuditTrail.where { id != null }.deleteAll()
            assert AuditTrail.count() == 0
        }

        author.handlerCalled = ""
    }

    void "Test persistedObjectVersion in update logging"() {
        given:
        setupData()
        def author = Author.findByName("Aaron")

        when:
        author.age = 40
        author.save(flush: true, failOnError: true)

        then:
        def events = AuditTrail.withCriteria { eq('className', 'test.Author') }
        events.size() == 1

        def first = events.find { it.propertyName == 'age' }
        first.persistedObjectVersion == author.version - 1
    }
    
    void "Test false/null differentiation"() {
        given:
        setupData()
        def author = Author.findByName("Aaron")

        when:
        author.famous = false
        author.save(flush: true, failOnError: true)

        then:
        def events = AuditTrail.withCriteria { eq('className', 'test.Author') }
        events.size() == 1

        def first = events.find { it.propertyName == 'famous' }
        first.oldValue == 'true'
        first.newValue == 'false'
        first.eventName == 'UPDATE'
    }
    
    void "Test persistedObjectVersion in update logging for domain class without version"() {
        given:
        setupData()
        def heliport = Heliport.findByCode('EGLW')

        when:
        heliport.name = "London Heliport"
        heliport.save(flush: true, failOnError: true)

        then:
        def events = AuditTrail.withCriteria { eq('className', 'test.Heliport') }
        events.size() == 1

        def first = events.find { it.propertyName == 'name' }
        first.persistedObjectVersion == null
    }

    void "Test basic update logging"() {
        given:
        setupData()
        def author = Author.findByName("Aaron")

        when:
        author.age = 40
        author.save(flush: true, failOnError: true)

        then:
        def events = AuditTrail.withCriteria { eq('className', 'test.Author') }
        events.size() == 1

        def first = events.find { it.propertyName == 'age' }
        first.oldValue == "37"
        first.newValue == "40"
        first.eventName == "UPDATE"
    }

    void "Test update to-one association"() {
        given:
        setupData()
        def author = Author.findByName("Aaron")

        when:
        author.publisher = Publisher.findByName("Random House")
        author.save(flush: true, failOnError: true)

        then:
        def events = AuditTrail.withCriteria { eq('className', 'test.Author') }
        events.size() == 1

        def first = events.find { it.propertyName == 'publisher' }
        first.oldValue == null
        first.newValue ==~ /\[id:ABC123\|Random House]test\.Publisher : \d+/
        first.eventName == "UPDATE"
    }

    void "Test two saves, one flush"() {
        given:
        setupData()
        def author = Author.findByName("Aaron")

        when:
        author.age = 40
        author.save(failOnError: true)
        author.age = 50
        author.save(flush: true, failOnError: true)

        then:
        def events = AuditTrail.withCriteria { eq('className', 'test.Author') }
        events.size() == 1

        def first = events.find { it.propertyName == 'age' }
        first.oldValue == "37"
        first.newValue == "50"
        first.eventName == "UPDATE"
    }

    void "Test conditional logging disabled"() {
        given:
        setupData()
        def publisher = Publisher.findByName("Random House")

        when: "is not active"
        publisher.name = "Spring"
        publisher.active = false
        publisher.save(flush: true, failOnError: true)

        then:
        def events = AuditTrail.withCriteria { eq('className', 'test.Publisher') }
        events.size() == 0
    }

    void "Test conditional logging enabled"() {
        given:
        setupData()
        def publisher = Publisher.findByName("Random House")

        when: "is not active"
        publisher.name = "Spring"
        publisher.active = true
        publisher.save(flush: true, failOnError: true)

        then:
        def events = AuditTrail.withCriteria { eq('className', 'test.Publisher') }
        events.size() == 1

        def first = events.first()
        first.persistedObjectId == 'ABC123|Spring'
        first.oldValue == 'Random House'
        first.newValue == 'Spring'
        first.propertyName == 'name'
        first.eventName == 'UPDATE'
    }

    void "Test globally ignored properties"() {
        given:
        setupData()
        def author = Author.findByName("Aaron")

        when:
        author.lastUpdatedBy = 'Aaron'
        author.save(flush: true, failOnError: true)

        then: "nothing logged"
        def events = AuditTrail.withCriteria { eq('className', 'test.Author') }
        events.size() == 0
    }

    void "Test locally ignored properties"() {
        given:
        setupData()
        Author.metaClass.getLogExcluded = { [ignore: ['name', 'famous', 'lastUpdated']] }
        def author = Author.findByName("Aaron")

        when:
        author.age = 50
        author.famous = false
        author.name = 'Bob'
        author.save(flush: true, failOnError: true)

        then: "ignored properties not logged"
        def events = AuditTrail.withCriteria { eq('className', 'test.Author') }

        events.size() == 1

        def first = events.find { it.propertyName == 'age' }
        first.persistedObjectVersion == author.version - 1
    }

    void "Test auditableProperties"() {
        given:
        setupData()
        Author.auditable = [auditableProperties: ['name', 'famous', 'lastUpdated']]
        def author = Author.findByName("Aaron")

        when:
        author.age = 50
        author.famous = false
        author.name = 'Bob'
        author.save(flush: true, failOnError: true)

        then: "only properties in auditableProperties are logged"
        def events = AuditTrail.withCriteria { eq('className', 'test.Author') }

        events.size() == 3

        ['name', 'famous', 'lastUpdated'].each { name ->
            assert events.find {it.propertyName == name}, "${name} was not logged"
        }
    }

    void "Test auditableProperties overrides ignore list"() {
        given:
        setupData()
        Author.auditable = [
          auditableProperties: ['name', 'famous', 'lastUpdated'],
          ignore: ['name', 'famous']
        ]
        def author = Author.findByName("Aaron")

        when:
        author.age = 50
        author.famous = false
        author.name = 'Bob'
        author.save(flush: true, failOnError: true)

        then: "only properties in auditableProperties are logged"
        def events = AuditTrail.withCriteria { eq('className', 'test.Author') }

        events.size() == 3

        ['name', 'famous', 'lastUpdated'].each { name ->
            assert events.find {it.propertyName == name}, "${name} was not logged"
        }
    }

    void "Test handler is called"() {
        given:
        setupData()
        def author = Author.findByName("Aaron")

        when:
        author.famous = false
        author.save(flush: true, failOnError: true)

        then: "verbose audit logging is created"
        def events = AuditTrail.withCriteria { eq('className', 'test.Author') }
        events.size() == 1

        and:
        author.handlerCalled == "onChange"
    }

    void "Test only handler is called"() {
        given:
        setupData()
        def author = Author.findByName("Aaron")
        Author.auditable = [handlersOnly: true]

        when:
        author.famous = false
        author.save(flush: true, failOnError: true)

        then: "nothing logged"
        def events = AuditTrail.withCriteria { eq('className', 'test.Author') }
        events.size() == 0

        and:
        author.handlerCalled == "onChange"
    }
}
