/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.xml.security.stax.impl.processor.input;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.xml.security.binding.excc14n.InclusiveNamespaces;
import org.apache.xml.security.binding.xmldsig.ReferenceType;
import org.apache.xml.security.binding.xmldsig.SignatureType;
import org.apache.xml.security.binding.xmldsig.TransformType;
import org.apache.xml.security.stax.config.JCEAlgorithmMapper;
import org.apache.xml.security.stax.ext.*;
import org.apache.xml.security.stax.ext.stax.XMLSecEndElement;
import org.apache.xml.security.stax.ext.stax.XMLSecEvent;
import org.apache.xml.security.stax.ext.stax.XMLSecStartElement;
import org.apache.xml.security.stax.impl.transformer.canonicalizer.Canonicalizer20010315_OmitCommentsTransformer;
import org.apache.xml.security.stax.impl.util.DigestOutputStream;
import org.apache.xml.security.stax.securityEvent.AlgorithmSuiteSecurityEvent;
import org.xmlsecurity.ns.configuration.AlgorithmType;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.*;

/**
 * @author $Author$
 * @version $Revision$ $Date$
 */
public abstract class AbstractSignatureReferenceVerifyInputProcessor extends AbstractInputProcessor {

    private static final transient Log logger = LogFactory.getLog(AbstractSignatureReferenceVerifyInputProcessor.class);

    private final SignatureType signatureType;
    private final SecurityToken securityToken;
    private final Map<String, ReferenceType> references;
    private final List<ReferenceType> processedReferences;

    public AbstractSignatureReferenceVerifyInputProcessor(
            SignatureType signatureType, SecurityToken securityToken,
            XMLSecurityProperties securityProperties) throws XMLSecurityException {
        super(securityProperties);
        this.signatureType = signatureType;
        this.securityToken = securityToken;

        List<ReferenceType> referencesTypeList = signatureType.getSignedInfo().getReference();
        references = new HashMap<String, ReferenceType>(referencesTypeList.size() + 1);
        processedReferences = new ArrayList<ReferenceType>(referencesTypeList.size());

        Iterator<ReferenceType> referenceTypeIterator = referencesTypeList.iterator();
        while (referenceTypeIterator.hasNext()) {
            ReferenceType referenceType = referenceTypeIterator.next();
            if (referenceType.getURI() == null) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK);
            }
            references.put(XMLSecurityUtils.dropReferenceMarker(referenceType.getURI()), referenceType);
        }
    }

    public SignatureType getSignatureType() {
        return signatureType;
    }

    public List<ReferenceType> getProcessedReferences() {
        return processedReferences;
    }

    public SecurityToken getSecurityToken() {
        return securityToken;
    }

    @Override
    public XMLSecEvent processNextHeaderEvent(InputProcessorChain inputProcessorChain)
            throws XMLStreamException, XMLSecurityException {
        return inputProcessorChain.processHeaderEvent();
    }

    @Override
    public XMLSecEvent processNextEvent(InputProcessorChain inputProcessorChain)
            throws XMLStreamException, XMLSecurityException {

        XMLSecEvent xmlSecEvent = inputProcessorChain.processEvent();
        switch (xmlSecEvent.getEventType()) {
            case XMLStreamConstants.START_ELEMENT:
                XMLSecStartElement xmlSecStartElement = xmlSecEvent.asStartElement();
                ReferenceType referenceType = matchesReferenceId(xmlSecStartElement);
                if (referenceType != null) {

                    if (processedReferences.contains(referenceType)) {
                        throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, "duplicateId");
                    }
                    InternalSignatureReferenceVerifier internalSignatureReferenceVerifier =
                        getSignatureReferenceVerifier(getSecurityProperties(), inputProcessorChain,
                                    referenceType, xmlSecStartElement.getName());
                    if (!internalSignatureReferenceVerifier.isFinished()) {
                        internalSignatureReferenceVerifier.processEvent(xmlSecEvent, inputProcessorChain);
                        inputProcessorChain.addProcessor(internalSignatureReferenceVerifier);
                    }
                    processedReferences.add(referenceType);
                    inputProcessorChain.getDocumentContext().setIsInSignedContent(
                            inputProcessorChain.getProcessors().indexOf(internalSignatureReferenceVerifier),
                            internalSignatureReferenceVerifier);
                    
                    // Fire a SecurityEvent
                    List<QName> elementPath = xmlSecStartElement.getElementPath();
                    processElementPath(elementPath, inputProcessorChain, xmlSecEvent);
                }
                break;
        }
        return xmlSecEvent;
    }
    
    protected abstract void processElementPath(
               List<QName> elementPath, InputProcessorChain inputProcessorChain, XMLSecEvent xmlSecEvent
    ) throws XMLSecurityException;

    protected ReferenceType matchesReferenceId(XMLSecStartElement xmlSecStartElement) {
        Attribute refId = getReferenceIDAttribute(xmlSecStartElement);
        if (refId != null) {
            return references.get(refId.getValue());
        }
        return null;
    }

    @Override
    public void doFinal(InputProcessorChain inputProcessorChain) throws XMLStreamException, XMLSecurityException {
        Iterator<Map.Entry<String, ReferenceType>> refEntryIterator = this.references.entrySet().iterator();
        while (refEntryIterator.hasNext()) {
            Map.Entry<String, ReferenceType> referenceTypeEntry = refEntryIterator.next();
            if (!processedReferences.contains(referenceTypeEntry.getValue())) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, "unprocessedEncryptionReferences");
            }
        }
        inputProcessorChain.doFinal();
    }
    
    protected InternalSignatureReferenceVerifier getSignatureReferenceVerifier(
            XMLSecurityProperties securityProperties, InputProcessorChain inputProcessorChain,
            ReferenceType referenceType, QName startElement) throws XMLSecurityException {
        return new InternalSignatureReferenceVerifier(securityProperties, inputProcessorChain, 
                                                      referenceType, startElement);
    }

    public class InternalSignatureReferenceVerifier extends AbstractInputProcessor {
        private ReferenceType referenceType;
        private Transformer transformer;
        private DigestOutputStream digestOutputStream;
        private OutputStream bufferedDigestOutputStream;
        private QName startElement;
        private int elementCounter = 0;
        private boolean finished = false;

        public InternalSignatureReferenceVerifier(
                XMLSecurityProperties securityProperties, InputProcessorChain inputProcessorChain,
                ReferenceType referenceType, QName startElement) throws XMLSecurityException {

            super(securityProperties);
            this.setStartElement(startElement);
            this.setReferenceType(referenceType);
            try {
                createMessageDigest(inputProcessorChain.getSecurityContext());
                buildTransformerChain(referenceType, inputProcessorChain);
            } catch (Exception e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
            }
        }

        protected AlgorithmType createMessageDigest(SecurityContext securityContext)
                throws XMLSecurityException, NoSuchAlgorithmException, NoSuchProviderException {
            AlgorithmType digestAlgorithm =
                    JCEAlgorithmMapper.getAlgorithmMapping(getReferenceType().getDigestMethod().getAlgorithm());

            MessageDigest messageDigest;
            if (digestAlgorithm.getJCEProvider() != null) {
                messageDigest = MessageDigest.getInstance(digestAlgorithm.getJCEName(), digestAlgorithm.getJCEProvider());
            } else {
                messageDigest = MessageDigest.getInstance(digestAlgorithm.getJCEName());
            }
            this.setDigestOutputStream(new DigestOutputStream(messageDigest));
            this.setBufferedDigestOutputStream(new BufferedOutputStream(this.getDigestOutputStream()));
            
            AlgorithmSuiteSecurityEvent algorithmSuiteSecurityEvent = new AlgorithmSuiteSecurityEvent();
            algorithmSuiteSecurityEvent.setAlgorithmURI(digestAlgorithm.getURI());
            algorithmSuiteSecurityEvent.setKeyUsage(XMLSecurityConstants.Dig);
            securityContext.registerSecurityEvent(algorithmSuiteSecurityEvent);

            return digestAlgorithm;
        }

        protected void buildTransformerChain(ReferenceType referenceType, InputProcessorChain inputProcessorChain)
                throws XMLSecurityException, XMLStreamException, NoSuchMethodException, InstantiationException,
                IllegalAccessException, InvocationTargetException {
            if (referenceType.getTransforms() == null) {
                // If no Transforms then just default to an Inclusive without comments transform
                Transformer transformer = new Canonicalizer20010315_OmitCommentsTransformer();
                transformer.setOutputStream(getBufferedDigestOutputStream());
                this.setTransformer(transformer);
                return;
            }
            
            List<TransformType> transformTypeList = referenceType.getTransforms().getTransform();

            Transformer parentTransformer = null;
            for (int i = transformTypeList.size() - 1; i >= 0; i--) {
                TransformType transformType = transformTypeList.get(i);

                InclusiveNamespaces inclusiveNamespacesType =
                        XMLSecurityUtils.getQNameType(transformType.getContent(),
                                XMLSecurityConstants.TAG_c14nExcl_InclusiveNamespaces);
                List<String> inclusiveNamespaces = inclusiveNamespacesType != null
                        ? inclusiveNamespacesType.getPrefixList()
                        : null;
                String algorithm = transformType.getAlgorithm();
                
                AlgorithmSuiteSecurityEvent algorithmSuiteSecurityEvent = new AlgorithmSuiteSecurityEvent();
                algorithmSuiteSecurityEvent.setAlgorithmURI(algorithm);
                algorithmSuiteSecurityEvent.setKeyUsage(XMLSecurityConstants.C14n);
                inputProcessorChain.getSecurityContext().registerSecurityEvent(algorithmSuiteSecurityEvent);
                
                if (parentTransformer != null) {
                    parentTransformer = XMLSecurityUtils.getTransformer(parentTransformer, inclusiveNamespaces, algorithm);
                } else {
                    parentTransformer =
                            XMLSecurityUtils.getTransformer(inclusiveNamespaces, this.getBufferedDigestOutputStream(), algorithm);
                }
            }
            this.setTransformer(parentTransformer);
        }

        @Override
        public XMLSecEvent processNextHeaderEvent(InputProcessorChain inputProcessorChain)
                throws XMLStreamException, XMLSecurityException {
            return inputProcessorChain.processHeaderEvent();
        }

        @Override
        public XMLSecEvent processNextEvent(InputProcessorChain inputProcessorChain)
                throws XMLStreamException, XMLSecurityException {
            XMLSecEvent xmlSecEvent = inputProcessorChain.processEvent();
            processEvent(xmlSecEvent, inputProcessorChain);
            return xmlSecEvent;
        }

        protected void processEvent(XMLSecEvent xmlSecEvent, InputProcessorChain inputProcessorChain)
                throws XMLStreamException, XMLSecurityException {

            getTransformer().transform(xmlSecEvent);
            switch (xmlSecEvent.getEventType()) {
                case XMLStreamConstants.START_ELEMENT:
                    this.elementCounter++;
                    break;
                case XMLStreamConstants.END_ELEMENT:
                    XMLSecEndElement xmlSecEndElement = xmlSecEvent.asEndElement();
                    this.elementCounter--;

                    if (this.elementCounter == 0 && xmlSecEndElement.getName().equals(getStartElement())) {
                        try {
                            getBufferedDigestOutputStream().close();
                        } catch (IOException e) {
                            throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_CHECK, e);
                        }

                        byte[] calculatedDigest = this.getDigestOutputStream().getDigestValue();
                        byte[] storedDigest = getReferenceType().getDigestValue();

                        if (logger.isDebugEnabled()) {
                            logger.debug("Calculated Digest: " + new String(Base64.encodeBase64(calculatedDigest)));
                            logger.debug("Stored Digest: " + new String(Base64.encodeBase64(storedDigest)));
                        }

                        if (!MessageDigest.isEqual(storedDigest, calculatedDigest)) {
                            throw new XMLSecurityException(
                                    XMLSecurityException.ErrorCode.FAILED_CHECK,
                                    "digestVerificationFailed", getReferenceType().getURI());
                        }
                        inputProcessorChain.removeProcessor(this);
                        inputProcessorChain.getDocumentContext().unsetIsInSignedContent(this);
                        setFinished(true);
                    }
                    break;
            }
        }

        public boolean isFinished() {
            return finished;
        }

        protected void setFinished(boolean finished) {
            this.finished = finished;
        }

        protected ReferenceType getReferenceType() {
            return referenceType;
        }

        protected void setReferenceType(ReferenceType referenceType) {
            this.referenceType = referenceType;
        }

        protected Transformer getTransformer() {
            return transformer;
        }

        protected void setTransformer(Transformer transformer) {
            this.transformer = transformer;
        }

        protected DigestOutputStream getDigestOutputStream() {
            return digestOutputStream;
        }

        protected void setDigestOutputStream(DigestOutputStream digestOutputStream) {
            this.digestOutputStream = digestOutputStream;
        }

        protected OutputStream getBufferedDigestOutputStream() {
            return bufferedDigestOutputStream;
        }

        protected void setBufferedDigestOutputStream(OutputStream bufferedDigestOutputStream) {
            this.bufferedDigestOutputStream = bufferedDigestOutputStream;
        }

        protected QName getStartElement() {
            return startElement;
        }

        protected void setStartElement(QName startElement) {
            this.startElement = startElement;
        }
    }
}